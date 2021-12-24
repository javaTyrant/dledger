/*
 * Copyright 2017-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openmessaging.storage.dledger;

import com.alibaba.fastjson.JSON;
import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.protocol.HeartBeatRequest;
import io.openmessaging.storage.dledger.protocol.HeartBeatResponse;
import io.openmessaging.storage.dledger.protocol.LeadershipTransferRequest;
import io.openmessaging.storage.dledger.protocol.LeadershipTransferResponse;
import io.openmessaging.storage.dledger.protocol.VoteRequest;
import io.openmessaging.storage.dledger.protocol.VoteResponse;
import io.openmessaging.storage.dledger.utils.DLedgerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_ALREADY_HAS_LEADER;
import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_ALREADY_VOTED;
import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_EXPIRED_LEDGER_TERM;
import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_EXPIRED_VOTE_TERM;
import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_SMALL_LEDGER_END_INDEX;
import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_TERM_NOT_READY;
import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_UNEXPECTED_LEADER;
import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.REJECT_UNKNOWN_LEADER;

public class DLedgerLeaderElector {
    //
    private static final Logger logger = LoggerFactory.getLogger(DLedgerLeaderElector.class);
    //随机数生成器，对应raft协议中选举超时时间是一随机数。
    private final Random random = new Random();
    //配置参数。
    private final DLedgerConfig dLedgerConfig;
    //节点状态机。
    private final MemberState memberState;
    //rpc服务，实现向集群内的节点发送心跳包、投票的RPC实现。
    private final DLedgerRpcService dLedgerRpcService;

    //as a server handler
    //record the last leader state
    //上次收到心跳包的时间戳。
    private volatile long lastLeaderHeartBeatTime = -1;
    //上次发送心跳包的时间戳。
    private volatile long lastSendHeartBeatTime = -1;
    //上次成功收到心跳包的时间戳。
    private volatile long lastSuccHeartBeatTime = -1;
    //一个心跳包的周期，默认为2s。
    private int heartBeatTimeIntervalMs = 2000;
    //允许最大的N个心跳周期内未收到心跳包，状态为Follower的节点只有超过 maxHeartBeatLeak * heartBeatTimeIntervalMs 的时间内未收到主节点的心跳包，
    //才会重新进入 Candidate 状态，重新下一轮的选举。
    private int maxHeartBeatLeak = 3;
    //作为客户端的参数.as a client.发送下一个心跳包的时间戳。
    private long nextTimeToRequestVote = -1;
    //是否应该立即发起投票。
    private volatile boolean needIncreaseTermImmediately = false;
    //最小的发送投票间隔时间，默认为300ms。
    private int minVoteIntervalMs = 300;
    //最大的发送投票的间隔，默认为1000ms。
    private int maxVoteIntervalMs = 1000;
    //注册的节点状态处理器，通过 addRoleChangeHandler 方法添加。
    private final List<RoleChangeHandler> roleChangeHandlers = new ArrayList<>();
    //上一次解析结果.
    private VoteResponse.ParseResult lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
    //上一次投票的开销。
    private long lastVoteCost = 0L;
    //状态维护
    private final StateMaintainer stateMaintainer = new StateMaintainer("StateMaintainer", logger);
    //获取选举权的任务.
    private final TakeLeadershipTask takeLeadershipTask = new TakeLeadershipTask();

    //
    public DLedgerLeaderElector(DLedgerConfig dLedgerConfig, MemberState memberState,
                                DLedgerRpcService dLedgerRpcService) {
        this.dLedgerConfig = dLedgerConfig;
        this.memberState = memberState;
        this.dLedgerRpcService = dLedgerRpcService;
        refreshIntervals(dLedgerConfig);
    }

    //通过 DLedgerLeaderElector 的 startup 方法启动状态管理机
    public void startup() {
        //
        stateMaintainer.start();
        //不会走到这个逻辑.
        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            roleChangeHandler.startup();
        }
    }

    public void shutdown() {
        stateMaintainer.shutdown();
        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            roleChangeHandler.shutdown();
        }
    }

    private void refreshIntervals(DLedgerConfig dLedgerConfig) {
        this.heartBeatTimeIntervalMs = dLedgerConfig.getHeartBeatTimeIntervalMs();
        this.maxHeartBeatLeak = dLedgerConfig.getMaxHeartBeatLeak();
        this.minVoteIntervalMs = dLedgerConfig.getMinVoteIntervalMs();
        this.maxVoteIntervalMs = dLedgerConfig.getMaxVoteIntervalMs();
    }

    public CompletableFuture<HeartBeatResponse> handleHeartBeat(HeartBeatRequest request) {

        if (!memberState.isPeerMember(request.getLeaderId())) {
            logger.warn("[BUG] [HandleHeartBeat] remoteId={} is an unknown member", request.getLeaderId());
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.UNKNOWN_MEMBER.getCode()));
        }

        if (memberState.getSelfId().equals(request.getLeaderId())) {
            logger.warn("[BUG] [HandleHeartBeat] selfId={} but remoteId={}", memberState.getSelfId(), request.getLeaderId());
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.UNEXPECTED_MEMBER.getCode()));
        }

        if (request.getTerm() < memberState.currTerm()) {
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
        } else if (request.getTerm() == memberState.currTerm()) {
            if (request.getLeaderId().equals(memberState.getLeaderId())) {
                lastLeaderHeartBeatTime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(new HeartBeatResponse());
            }
        }

        //abnormal case
        //hold the lock to get the latest term and leaderId
        synchronized (memberState) {
            if (request.getTerm() < memberState.currTerm()) {
                return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
            } else if (request.getTerm() == memberState.currTerm()) {
                if (memberState.getLeaderId() == null) {
                    changeRoleToFollower(request.getTerm(), request.getLeaderId());
                    return CompletableFuture.completedFuture(new HeartBeatResponse());
                } else if (request.getLeaderId().equals(memberState.getLeaderId())) {
                    lastLeaderHeartBeatTime = System.currentTimeMillis();
                    return CompletableFuture.completedFuture(new HeartBeatResponse());
                } else {
                    //this should not happen, but if happened
                    logger.error("[{}][BUG] currTerm {} has leader {}, but received leader {}", memberState.getSelfId(), memberState.currTerm(), memberState.getLeaderId(), request.getLeaderId());
                    return CompletableFuture.completedFuture(new HeartBeatResponse().code(DLedgerResponseCode.INCONSISTENT_LEADER.getCode()));
                }
            } else {
                //To make it simple, for larger term, do not change to follower immediately
                //first change to candidate, and notify the state-maintainer thread
                changeRoleToCandidate(request.getTerm());
                needIncreaseTermImmediately = true;
                //TOOD notify
                return CompletableFuture.completedFuture(new HeartBeatResponse().code(DLedgerResponseCode.TERM_NOT_READY.getCode()));
            }
        }
    }

    public void changeRoleToLeader(long term) {
        synchronized (memberState) {
            if (memberState.currTerm() == term) {
                memberState.changeToLeader(term);
                lastSendHeartBeatTime = -1;
                handleRoleChange(term, MemberState.Role.LEADER);
                logger.info("[{}] [ChangeRoleToLeader] from term: {} and currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            } else {
                logger.warn("[{}] skip to be the leader in term: {}, but currTerm is: {}", memberState.getSelfId(), term, memberState.currTerm());
            }
        }
    }

    public void changeRoleToCandidate(long term) {
        synchronized (memberState) {
            if (term >= memberState.currTerm()) {
                memberState.changeToCandidate(term);
                handleRoleChange(term, MemberState.Role.CANDIDATE);
                logger.info("[{}] [ChangeRoleToCandidate] from term: {} and currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            } else {
                logger.info("[{}] skip to be candidate in term: {}, but currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            }
        }
    }

    //just for test
    public void testRevote(long term) {
        changeRoleToCandidate(term);
        lastParseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
        nextTimeToRequestVote = -1;
    }

    public void changeRoleToFollower(long term, String leaderId) {
        logger.info("[{}][ChangeRoleToFollower] from term: {} leaderId: {} and currTerm: {}", memberState.getSelfId(), term, leaderId, memberState.currTerm());
        lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
        memberState.changeToFollower(term, leaderId);
        lastLeaderHeartBeatTime = System.currentTimeMillis();
        handleRoleChange(term, MemberState.Role.FOLLOWER);
    }

    //处理选票
    public CompletableFuture<VoteResponse> handleVote(VoteRequest request, boolean self) {
        //hold the lock to get the latest term, leaderId, ledgerEndIndex
        synchronized (memberState) {
            //拒绝未知的Leader
            if (!memberState.isPeerMember(request.getLeaderId())) {
                logger.warn("[BUG] [HandleVote] remoteId={} is an unknown member", request.getLeaderId());
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_UNKNOWN_LEADER));
            }
            //拒绝不符合期望的Leader.
            if (!self && memberState.getSelfId().equals(request.getLeaderId())) {
                logger.warn("[BUG] [HandleVote] selfId={} but remoteId={}", memberState.getSelfId(), request.getLeaderId());
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_UNEXPECTED_LEADER));
            }
            //拒绝任期不满足
            if (request.getLedgerEndTerm() < memberState.getLedgerEndTerm()) {
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_EXPIRED_LEDGER_TERM));
            } else if (request.getLedgerEndTerm() == memberState.getLedgerEndTerm() && request.getLedgerEndIndex() < memberState.getLedgerEndIndex()) {
                //endIndex不满足.
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_SMALL_LEDGER_END_INDEX));
            }
            //
            if (request.getTerm() < memberState.currTerm()) {
                //
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_EXPIRED_VOTE_TERM));
            } else if (request.getTerm() == memberState.currTerm()) {//任期相等.
                if (memberState.currVoteFor() == null) {
                    //let it go
                } else if (memberState.currVoteFor().equals(request.getLeaderId())) {
                    //repeat just let it go
                } else {
                    if (memberState.getLeaderId() != null) {//
                        return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_ALREADY_HAS_LEADER));
                    } else {
                        return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_ALREADY_VOTED));
                    }
                }
            } else {
                //stepped down by larger term
                changeRoleToCandidate(request.getTerm());
                needIncreaseTermImmediately = true;
                //only can handleVote when the term is consistent
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(REJECT_TERM_NOT_READY));
            }

            if (request.getTerm() < memberState.getLedgerEndTerm()) {
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.getLedgerEndTerm()).voteResult(VoteResponse.RESULT.REJECT_TERM_SMALL_THAN_LEDGER));
            }

            if (!self && isTakingLeadership() && request.getLedgerEndTerm() == memberState.getLedgerEndTerm() && memberState.getLedgerEndIndex() >= request.getLedgerEndIndex()) {
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_TAKING_LEADERSHIP));
            }
            //成功获取这张选票.主节点为了维持其领导地位，需要定时向从节点发送心跳包，接下来我们重点看一下心跳包的发送与响应。
            memberState.setCurrVoteFor(request.getLeaderId());
            //都满足,接受选票.
            return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.ACCEPT));
        }
    }

    //Leader才能调用的方法.
    private void sendHeartbeats(long term, String leaderId) throws Exception {
        final AtomicInteger allNum = new AtomicInteger(1);
        final AtomicInteger succNum = new AtomicInteger(1);
        final AtomicInteger notReadyNum = new AtomicInteger(0);
        final AtomicLong maxTerm = new AtomicLong(-1);
        final AtomicBoolean inconsistLeader = new AtomicBoolean(false);
        final CountDownLatch beatLatch = new CountDownLatch(1);
        long startHeartbeatTimeMs = System.currentTimeMillis();
        //遍历服务器.
        for (String id : memberState.getPeerMap().keySet()) {
            //调过自己.
            if (memberState.getSelfId().equals(id)) {
                continue;
            }
            //心跳请求.
            HeartBeatRequest heartBeatRequest = new HeartBeatRequest();
            heartBeatRequest.setGroup(memberState.getGroup());
            heartBeatRequest.setLocalId(memberState.getSelfId());
            heartBeatRequest.setRemoteId(id);
            heartBeatRequest.setLeaderId(leaderId);
            heartBeatRequest.setTerm(term);
            //发送心跳.
            CompletableFuture<HeartBeatResponse> future = dLedgerRpcService.heartBeat(heartBeatRequest);
            //成功.
            future.whenComplete((HeartBeatResponse x, Throwable ex) -> {
                try {
                    if (ex != null) {
                        memberState.getPeersLiveTable().put(id, Boolean.FALSE);
                        throw ex;
                    }
                    //根据心跳的返回值判断.
                    switch (DLedgerResponseCode.valueOf(x.getCode())) {
                        //成功.
                        case SUCCESS:
                            //数量+1
                            succNum.incrementAndGet();
                            break;
                        case EXPIRED_TERM:
                            maxTerm.set(x.getTerm());
                            break;
                        case INCONSISTENT_LEADER:
                            inconsistLeader.compareAndSet(false, true);
                            break;
                        case TERM_NOT_READY:
                            notReadyNum.incrementAndGet();
                            break;
                        default:
                            break;
                    }
                    //网络异常
                    if (x.getCode() == DLedgerResponseCode.NETWORK_ERROR.getCode())
                        memberState.getPeersLiveTable().put(id, Boolean.FALSE);
                    else
                        memberState.getPeersLiveTable().put(id, Boolean.TRUE);
                    //成功的过半.或者 成功+没准备好的过半.
                    if (memberState.isQuorum(succNum.get())
                            || memberState.isQuorum(succNum.get() + notReadyNum.get())) {
                        beatLatch.countDown();
                    }
                } catch (Throwable t) {
                    logger.error("heartbeat response failed", t);
                } finally {
                    allNum.incrementAndGet();
                    if (allNum.get() == memberState.peerSize()) {
                        beatLatch.countDown();
                    }
                }
            });
        }
        //等待.如果上面countDown了,就接着往下执行.否则接着等待.
        beatLatch.await(heartBeatTimeIntervalMs, TimeUnit.MILLISECONDS);
        //如果过半,则更新上一次心跳时间.
        if (memberState.isQuorum(succNum.get())) {
            lastSuccHeartBeatTime = System.currentTimeMillis();
        } else {
            //非过半的处理逻辑.
            logger.info("[{}] Parse heartbeat responses in cost={} term={} allNum={} succNum={} notReadyNum={} inconsistLeader={} maxTerm={} peerSize={} lastSuccHeartBeatTime={}",
                    memberState.getSelfId(), DLedgerUtils.elapsed(startHeartbeatTimeMs), term, allNum.get(), succNum.get(), notReadyNum.get(), inconsistLeader.get(), maxTerm.get(), memberState.peerSize(), new Timestamp(lastSuccHeartBeatTime));
            if (memberState.isQuorum(succNum.get() + notReadyNum.get())) {
                lastSendHeartBeatTime = -1;
            } else if (maxTerm.get() > term) {
                changeRoleToCandidate(maxTerm.get());
            } else if (inconsistLeader.get()) {
                changeRoleToCandidate(term);
            } else if (DLedgerUtils.elapsed(lastSuccHeartBeatTime) > (long) maxHeartBeatLeak * heartBeatTimeIntervalMs) {
                changeRoleToCandidate(term);
            }
        }
    }

    //成为Leader后
    private void maintainAsLeader() throws Exception {
        //首先判断上一次发送心跳的时间与当前时间的差值是否大于心跳包发送间隔，如果超过，则说明需要发送心跳包。
        if (DLedgerUtils.elapsed(lastSendHeartBeatTime) > heartBeatTimeIntervalMs) {
            long term;
            String leaderId;
            synchronized (memberState) {
                //如果当前不是leader节点，则直接返回，主要是为了二次判断。
                if (!memberState.isLeader()) {
                    //stop sending
                    return;
                }
                term = memberState.currTerm();
                leaderId = memberState.getLeaderId();
                //重置心跳包发送计时器。
                lastSendHeartBeatTime = System.currentTimeMillis();
            }
            //向集群内的所有节点发送心跳包，稍后会详细介绍心跳包的发送。
            sendHeartbeats(term, leaderId);
        }
    }

    //当 Candidate 状态的节点在收到主节点发送的心跳包后，会将状态变更为follower，那我们先来看一下在follower状态下，节点会做些什么事情？
    //如果maxHeartBeatLeak (默认为3)个心跳包周期内未收到心跳，则将状态变更为Candidate。
    private void maintainAsFollower() {
        //上次心跳时间大于2 * heartBeatTimeIntervalMs,说明很久没有收到心跳了.
        if (DLedgerUtils.elapsed(lastLeaderHeartBeatTime) > 2L * heartBeatTimeIntervalMs) {
            synchronized (memberState) {
                //是Follower且时间满足
                if (memberState.isFollower()
                        && (DLedgerUtils.elapsed(lastLeaderHeartBeatTime) > (long) maxHeartBeatLeak * heartBeatTimeIntervalMs)) {
                    logger.info("[{}][HeartBeatTimeOut] lastLeaderHeartBeatTime: {} heartBeatTimeIntervalMs: {} lastLeader={}",
                            memberState.getSelfId(), new Timestamp(lastLeaderHeartBeatTime), heartBeatTimeIntervalMs, memberState.getLeaderId());
                    //改变状态.
                    changeRoleToCandidate(memberState.currTerm());
                }
            }
        }
    }

    /**
     * @param term           发起投票的节点当前的投票轮次。
     * @param ledgerEndTerm  发起投票节点维护的已知的最大投票轮次。
     * @param ledgerEndIndex 发起投票节点维护的已知的最大日志条目索引。
     */
    private List<CompletableFuture<VoteResponse>> voteForQuorumResponses(long term,
                                                                         long ledgerEndTerm,
                                                                         long ledgerEndIndex)
            throws Exception {
        List<CompletableFuture<VoteResponse>> responses = new ArrayList<>();
        //
        for (String id : memberState.getPeerMap().keySet()) {
            //选票.
            VoteRequest voteRequest = new VoteRequest();
            //
            voteRequest.setGroup(memberState.getGroup());
            voteRequest.setLedgerEndIndex(ledgerEndIndex);
            voteRequest.setLedgerEndTerm(ledgerEndTerm);
            voteRequest.setLeaderId(memberState.getSelfId());
            voteRequest.setTerm(term);
            voteRequest.setRemoteId(id);
            //返回结果.
            CompletableFuture<VoteResponse> voteResponse;
            //如果是leader,处理选票
            if (memberState.getSelfId().equals(id)) {
                voteResponse = handleVote(voteRequest, true);
            } else {
                //否则,去拉票.
                voteResponse = dLedgerRpcService.vote(voteRequest);
            }
            responses.add(voteResponse);
        }
        return responses;
    }

    private boolean isTakingLeadership() {
        return memberState.getSelfId().equals(dLedgerConfig.getPreferredLeaderId())
                || memberState.getTermToTakeLeadership() == memberState.currTerm();
    }

    private long getNextTimeToRequestVote() {
        if (isTakingLeadership()) {
            return System.currentTimeMillis() + dLedgerConfig.getMinTakeLeadershipVoteIntervalMs() +
                    random.nextInt(dLedgerConfig.getMaxTakeLeadershipVoteIntervalMs() - dLedgerConfig.getMinTakeLeadershipVoteIntervalMs());
        }
        return System.currentTimeMillis() + minVoteIntervalMs + random.nextInt(maxVoteIntervalMs - minVoteIntervalMs);
    }

    private void maintainAsCandidate() throws Exception {
        //for candidate
        if (System.currentTimeMillis() < nextTimeToRequestVote && !needIncreaseTermImmediately) {
            return;
        }
        //任期
        long term;
        //Leader节点当前的投票轮次。
        long ledgerEndTerm;
        //当前日志的最大序列，即下一条日志的开始 index，在日志复制部分会详细介绍。
        long ledgerEndIndex;
        //加锁.
        synchronized (memberState) {
            //节点状态校验
            if (!memberState.isCandidate()) {
                return;
            }
            //如果最后一次的选举结果是等待下一次投票 或者 需要立马增加term.
            if (lastParseResult == VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT || needIncreaseTermImmediately) {
                //prev.
                long prevTerm = memberState.currTerm();
                //next.
                term = memberState.nextTerm();
                logger.info("{}_[INCREASE_TERM] from {} to {}", memberState.getSelfId(), prevTerm, term);
                //res
                lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            } else {
                //任期不变.
                term = memberState.currTerm();
            }
            //从memberState获取.memberState的赋值逻辑.
            ledgerEndIndex = memberState.getLedgerEndIndex();
            //
            ledgerEndTerm = memberState.getLedgerEndTerm();
        }
        //需要增加任期吗?
        if (needIncreaseTermImmediately) {
            //
            nextTimeToRequestVote = getNextTimeToRequestVote();
            needIncreaseTermImmediately = false;
            return;
        }
        //
        long startVoteTimeMs = System.currentTimeMillis();
        //和其他的节点通信.
        final List<CompletableFuture<VoteResponse>> quorumVoteResponses =
                voteForQuorumResponses(term, ledgerEndTerm, ledgerEndIndex);
        //已知的最大投票轮次。
        final AtomicLong knownMaxTermInGroup = new AtomicLong(term);
        //所有投票票数。
        final AtomicInteger allNum = new AtomicInteger(0);
        //有效投票数。
        final AtomicInteger validNum = new AtomicInteger(0);
        //获得的投票数。
        final AtomicInteger acceptedNum = new AtomicInteger(0);
        //未准备投票的节点数量，如果对端节点的投票轮次小于发起投票的轮次，则认为对端未准备好，对端节点使用本次的轮次进入
        final AtomicInteger notReadyTermNum = new AtomicInteger(0);
        //发起投票的节点的ledgerEndTerm小于对端节点的个数。
        final AtomicInteger biggerLedgerNum = new AtomicInteger(0);
        //是否已经有Leader了.
        final AtomicBoolean alreadyHasLeader = new AtomicBoolean(false);
        //
        CountDownLatch voteLatch = new CountDownLatch(1);
        //遍历选票.
        for (CompletableFuture<VoteResponse> future : quorumVoteResponses) {
            //
            future.whenComplete((VoteResponse x, Throwable ex) -> {
                try {
                    if (ex != null) {
                        throw ex;
                    }
                    logger.info("[{}][GetVoteResponse] {}", memberState.getSelfId(), JSON.toJSONString(x));
                    //有效++
                    if (x.getVoteResult() != VoteResponse.RESULT.UNKNOWN) {
                        validNum.incrementAndGet();
                    }
                    //加锁.
                    synchronized (knownMaxTermInGroup) {
                        //
                        switch (x.getVoteResult()) {
                            //赞成票，acceptedNum加一，只有得到的赞成票超过集群节点数量的一半才能成为Leader。
                            case ACCEPT:
                                acceptedNum.incrementAndGet();
                                break;
                            //拒绝票，原因是已经投了其他节点的票。
                            case REJECT_ALREADY_VOTED:
                            case REJECT_TAKING_LEADERSHIP:
                                break;
                            //拒绝票，原因是因为集群中已经存在Leaer了。alreadyHasLeader设置为true，无需在判断其他投票结果了，结束本轮投票。
                            case REJECT_ALREADY_HAS_LEADER:
                                alreadyHasLeader.compareAndSet(false, true);
                                break;
                            //拒绝票，如果自己维护的term小于远端维护的ledgerEndTerm，则返回该结果，如果对端的team大于自己的team，
                            //需要记录对端最大的投票轮次，以便更新自己的投票轮次。
                            case REJECT_TERM_SMALL_THAN_LEDGER:
                                //拒绝票，如果自己维护的term小于远端维护的term，更新自己维护的投票轮次。
                            case REJECT_EXPIRED_VOTE_TERM:
                                if (x.getTerm() > knownMaxTermInGroup.get()) {
                                    knownMaxTermInGroup.set(x.getTerm());
                                }
                                break;
                            //拒绝票，如果自己维护的 ledgerTerm小于对端维护的ledgerTerm，则返回该结果。如果是此种情况，增加计数器- biggerLedgerNum的值。
                            case REJECT_EXPIRED_LEDGER_TERM:
                                //拒绝票，如果对端的ledgerTeam与自己维护的ledgerTeam相等，但是自己维护的dedgerEndIndex小于对端维护的值，返回该值，增加biggerLedgerNum计数器的值。
                            case REJECT_SMALL_LEDGER_END_INDEX:
                                biggerLedgerNum.incrementAndGet();
                                break;
                            //拒绝票，对端的投票轮次小于自己的team，则认为对端还未准备好投票，对端使用自己的投票轮次，是自己进入到Candidate状态。
                            case REJECT_TERM_NOT_READY:
                                notReadyTermNum.incrementAndGet();
                                break;
                            default:
                                break;

                        }
                    }
                    if (alreadyHasLeader.get()
                            || memberState.isQuorum(acceptedNum.get())
                            || memberState.isQuorum(acceptedNum.get() + notReadyTermNum.get())) {
                        voteLatch.countDown();
                    }
                } catch (Throwable t) {
                    logger.error("vote response failed", t);
                } finally {
                    allNum.incrementAndGet();
                    if (allNum.get() == memberState.peerSize()) {
                        voteLatch.countDown();
                    }
                }
            });

        }

        try {
            voteLatch.await(2000 + random.nextInt(maxVoteIntervalMs), TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {

        }
        //
        lastVoteCost = DLedgerUtils.elapsed(startVoteTimeMs);
        //
        VoteResponse.ParseResult parseResult;
        if (knownMaxTermInGroup.get() > term) {
            parseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
            nextTimeToRequestVote = getNextTimeToRequestVote();
            changeRoleToCandidate(knownMaxTermInGroup.get());
        } else if (alreadyHasLeader.get()) {
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote() + (long) heartBeatTimeIntervalMs * maxHeartBeatLeak;
        } else if (!memberState.isQuorum(validNum.get())) {
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote();
        } else if (!memberState.isQuorum(validNum.get() - biggerLedgerNum.get())) {
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote() + maxVoteIntervalMs;
        } else if (memberState.isQuorum(acceptedNum.get())) {
            parseResult = VoteResponse.ParseResult.PASSED;
        } else if (memberState.isQuorum(acceptedNum.get() + notReadyTermNum.get())) {
            parseResult = VoteResponse.ParseResult.REVOTE_IMMEDIATELY;
        } else {
            parseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
            nextTimeToRequestVote = getNextTimeToRequestVote();
        }
        //温馨提示：在讲解关键点之前，我们先定义先将
        //（当前时间戳 + 上次投票的开销 + 最小投票间隔(300ms) + （1000- 300 ）之间的随机值）定义为“ 1个常规计时器”。
        lastParseResult = parseResult;
        //
        logger.info("[{}] [PARSE_VOTE_RESULT] cost={} term={} memberNum={} allNum={} acceptedNum={} notReadyTermNum={} biggerLedgerNum={} alreadyHasLeader={} maxTerm={} result={}",
                memberState.getSelfId(), lastVoteCost, term, memberState.peerSize(), allNum, acceptedNum, notReadyTermNum, biggerLedgerNum, alreadyHasLeader, knownMaxTermInGroup.get(), parseResult);
        //
        if (parseResult == VoteResponse.ParseResult.PASSED) {
            logger.info("[{}] [VOTE_RESULT] has been elected to be the leader in term {}", memberState.getSelfId(), term);
            changeRoleToLeader(term);
        }
    }

    /**
     * The core method of maintainer.
     * Run the specified logic according to the current role:
     * candidate => propose a vote.
     * leader => send heartbeats to followers, and step down to candidate when quorum followers do not respond.
     * follower => accept heartbeats, and change to candidate when no heartbeat from leader.
     */
    private void maintainState() throws Exception {
        //发现其初始状态为 CANDIDATE。那我们接下从 maintainAsCandidate 方法开始跟进。
        if (memberState.isLeader()) {
            maintainAsLeader();
        } else if (memberState.isFollower()) {
            maintainAsFollower();
        } else {
            //温馨提示：在raft协议中，节点的状态默认为follower，DLedger的实现从candidate开始，
            //一开始，集群内的所有节点都会尝试发起投票，这样第一轮要达成选举几乎不太可能。
            maintainAsCandidate();
        }
    }

    private void handleRoleChange(long term, MemberState.Role role) {
        try {
            takeLeadershipTask.check(term, role);
        } catch (Throwable t) {
            logger.error("takeLeadershipTask.check failed. ter={}, role={}", term, role, t);
        }

        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            try {
                roleChangeHandler.handle(term, role);
            } catch (Throwable t) {
                logger.warn("Handle role change failed term={} role={} handler={}", term, role, roleChangeHandler.getClass(), t);
            }
        }
    }

    @SuppressWarnings("unused")
    public void addRoleChangeHandler(RoleChangeHandler roleChangeHandler) {
        if (!roleChangeHandlers.contains(roleChangeHandler)) {
            roleChangeHandlers.add(roleChangeHandler);
        }
    }

    public CompletableFuture<LeadershipTransferResponse> handleLeadershipTransfer(
            LeadershipTransferRequest request) throws Exception {
        logger.info("handleLeadershipTransfer: {}", request);
        synchronized (memberState) {
            if (memberState.currTerm() != request.getTerm()) {
                logger.warn("[BUG] [HandleLeaderTransfer] currTerm={} != request.term={}", memberState.currTerm(), request.getTerm());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.INCONSISTENT_TERM.getCode()));
            }

            if (!memberState.isLeader()) {
                logger.warn("[BUG] [HandleLeaderTransfer] selfId={} is not leader", request.getLeaderId());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.NOT_LEADER.getCode()));
            }

            if (memberState.getTransferee() != null) {
                logger.warn("[BUG] [HandleLeaderTransfer] transferee={} is already set", memberState.getTransferee());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.LEADER_TRANSFERRING.getCode()));
            }

            memberState.setTransferee(request.getTransfereeId());
        }
        LeadershipTransferRequest takeLeadershipRequest = new LeadershipTransferRequest();
        takeLeadershipRequest.setGroup(memberState.getGroup());
        takeLeadershipRequest.setLeaderId(memberState.getLeaderId());
        takeLeadershipRequest.setLocalId(memberState.getSelfId());
        takeLeadershipRequest.setRemoteId(request.getTransfereeId());
        takeLeadershipRequest.setTerm(request.getTerm());
        takeLeadershipRequest.setTakeLeadershipLedgerIndex(memberState.getLedgerEndIndex());
        takeLeadershipRequest.setTransferId(memberState.getSelfId());
        takeLeadershipRequest.setTransfereeId(request.getTransfereeId());
        if (memberState.currTerm() != request.getTerm()) {
            logger.warn("[HandleLeaderTransfer] term changed, cur={} , request={}", memberState.currTerm(), request.getTerm());
            return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
        }

        return dLedgerRpcService.leadershipTransfer(takeLeadershipRequest).thenApply(response -> {
            synchronized (memberState) {
                if (response.getCode() != DLedgerResponseCode.SUCCESS.getCode() ||
                        (memberState.currTerm() == request.getTerm() && memberState.getTransferee() != null)) {
                    logger.warn("leadershipTransfer failed, set transferee to null");
                    memberState.setTransferee(null);
                }
            }
            return response;
        });
    }

    public CompletableFuture<LeadershipTransferResponse> handleTakeLeadership(
            LeadershipTransferRequest request) {
        logger.debug("handleTakeLeadership.request={}", request);
        synchronized (memberState) {
            if (memberState.currTerm() != request.getTerm()) {
                logger.warn("[BUG] [handleTakeLeadership] currTerm={} != request.term={}", memberState.currTerm(), request.getTerm());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.INCONSISTENT_TERM.getCode()));
            }

            long targetTerm = request.getTerm() + 1;
            memberState.setTermToTakeLeadership(targetTerm);
            CompletableFuture<LeadershipTransferResponse> response = new CompletableFuture<>();
            takeLeadershipTask.update(request, response);
            changeRoleToCandidate(targetTerm);
            needIncreaseTermImmediately = true;
            return response;
        }
    }

    private class TakeLeadershipTask {
        //
        private LeadershipTransferRequest request;
        //
        private CompletableFuture<LeadershipTransferResponse> responseFuture;

        public synchronized void update(LeadershipTransferRequest request,
                                        CompletableFuture<LeadershipTransferResponse> responseFuture) {
            this.request = request;
            this.responseFuture = responseFuture;
        }

        public synchronized void check(long term, MemberState.Role role) {
            logger.trace("TakeLeadershipTask called, term={}, role={}", term, role);
            if (memberState.getTermToTakeLeadership() == -1 || responseFuture == null) {
                return;
            }
            //
            LeadershipTransferResponse response;
            if (term > memberState.getTermToTakeLeadership()) {
                response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.EXPIRED_TERM.getCode());
            } else if (term == memberState.getTermToTakeLeadership()) {
                switch (role) {
                    case LEADER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.SUCCESS.getCode());
                        break;
                    case FOLLOWER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.TAKE_LEADERSHIP_FAILED.getCode());
                        break;
                    default:
                        return;
                }
            } else {
                /*
                 * The node may receive heartbeat before term increase as a candidate,
                 * then it will be follower and term < TermToTakeLeadership
                 */
                if (role == MemberState.Role.FOLLOWER) {
                    response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.TAKE_LEADERSHIP_FAILED.getCode());
                } else {
                    response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.INTERNAL_ERROR.getCode());
                }
            }

            responseFuture.complete(response);
            logger.info("TakeLeadershipTask finished. request={}, response={}, term={}, role={}", request, response, term, role);
            memberState.setTermToTakeLeadership(-1);
            responseFuture = null;
            request = null;
        }
    }

    public interface RoleChangeHandler {
        //
        void handle(long term, MemberState.Role role);

        void startup();

        void shutdown();
    }

    //
    public class StateMaintainer extends ShutdownAbleThread {

        public StateMaintainer(String name, Logger logger) {
            super(name, logger);
        }

        @Override
        public void doWork() {
            //
            try {
                //
                if (DLedgerLeaderElector.this.dLedgerConfig.isEnableLeaderElector()) {
                    //内部刷新.
                    DLedgerLeaderElector.this.refreshIntervals(dLedgerConfig);
                    //维护状态.
                    DLedgerLeaderElector.this.maintainState();
                }
                sleep(10);
            } catch (Throwable t) {
                DLedgerLeaderElector.logger.error("Error in heartbeat", t);
            }
        }

    }
}
