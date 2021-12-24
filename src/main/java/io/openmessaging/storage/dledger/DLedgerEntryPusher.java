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
import io.openmessaging.storage.dledger.entry.DLedgerEntry;
import io.openmessaging.storage.dledger.exception.DLedgerException;
import io.openmessaging.storage.dledger.protocol.AppendEntryResponse;
import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.protocol.PushEntryRequest;
import io.openmessaging.storage.dledger.protocol.PushEntryResponse;
import io.openmessaging.storage.dledger.store.DLedgerMemoryStore;
import io.openmessaging.storage.dledger.store.DLedgerStore;
import io.openmessaging.storage.dledger.store.file.DLedgerMmapFileStore;
import io.openmessaging.storage.dledger.utils.DLedgerUtils;
import io.openmessaging.storage.dledger.utils.Pair;
import io.openmessaging.storage.dledger.utils.PreConditions;
import io.openmessaging.storage.dledger.utils.Quota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

//1.raft 协议中有一个非常重要的概念：已提交日志序号，该如何实现。
//2.客户端向 DLedger 集群发送一条日志，必须得到集群中大多数节点的认可才能被认为写入成功
//3.raft 协议中追加、提交两个动作如何实现。

//DLedger 日志转发与处理核心类，该内会启动如下3个对象，其分别对应一个线程。
public class DLedgerEntryPusher {

    private static final Logger logger = LoggerFactory.getLogger(DLedgerEntryPusher.class);
    //多副本相关配置。
    private final DLedgerConfig dLedgerConfig;
    //存储实现类。
    private final DLedgerStore dLedgerStore;
    //节点状态机。
    private final MemberState memberState;
    //RPC 服务实现类，用于集群内的其他节点进行网络通讯。
    private final DLedgerRpcService dLedgerRpcService;
    //每个节点基于投票轮次的当前水位线标记。键值为投票轮次
    private final Map<Long, ConcurrentMap<String, Long>> peerWaterMarksByTerm = new ConcurrentHashMap<>();
    //用于存放追加请求的响应结果(Future模式)。
    private final Map<Long, ConcurrentMap<Long, TimeoutFuture<AppendEntryResponse>>> pendingAppendResponsesByTerm = new ConcurrentHashMap<>();
    //从节点上开启的线程，用于接收主节点的 push 请求（append、commit、append）。
    private final EntryHandler entryHandler;
    //主节点上的追加请求投票器。
    private final QuorumAckChecker quorumAckChecker;
    //主节点日志请求转发器，向从节点复制消息等。
    private final Map<String, EntryDispatcher> dispatcherMap = new HashMap<>();

    //
    public DLedgerEntryPusher(DLedgerConfig dLedgerConfig, MemberState memberState, DLedgerStore dLedgerStore,
                              DLedgerRpcService dLedgerRpcService) {
        this.dLedgerConfig = dLedgerConfig;
        this.memberState = memberState;
        this.dLedgerStore = dLedgerStore;
        this.dLedgerRpcService = dLedgerRpcService;
        for (String peer : memberState.getPeerMap().keySet()) {
            //把其他的节点添加到需要分发的map里.
            if (!peer.equals(memberState.getSelfId())) {
                dispatcherMap.put(peer, new EntryDispatcher(peer, logger));
            }
        }
        this.entryHandler = new EntryHandler(logger);
        this.quorumAckChecker = new QuorumAckChecker(logger);
    }

    public void startup() {
        //处理选票
        entryHandler.start();
        //ack
        quorumAckChecker.start();
        for (EntryDispatcher dispatcher : dispatcherMap.values()) {
            dispatcher.start();
        }
    }

    public void shutdown() {
        entryHandler.shutdown();
        quorumAckChecker.shutdown();
        for (EntryDispatcher dispatcher : dispatcherMap.values()) {
            dispatcher.shutdown();
        }
    }

    public CompletableFuture<PushEntryResponse> handlePush(PushEntryRequest request) throws Exception {
        return entryHandler.handlePush(request);
    }

    private void checkTermForWaterMark(long term, String env) {
        if (!peerWaterMarksByTerm.containsKey(term)) {
            logger.info("Initialize the watermark in {} for term={}", env, term);
            ConcurrentMap<String, Long> waterMarks = new ConcurrentHashMap<>();
            for (String peer : memberState.getPeerMap().keySet()) {
                waterMarks.put(peer, -1L);
            }
            peerWaterMarksByTerm.putIfAbsent(term, waterMarks);
        }
    }

    private void checkTermForPendingMap(long term, String env) {
        if (!pendingAppendResponsesByTerm.containsKey(term)) {
            logger.info("Initialize the pending append map in {} for term={}", env, term);
            pendingAppendResponsesByTerm.putIfAbsent(term, new ConcurrentHashMap<>());
        }
    }

    private void updatePeerWaterMark(long term, String peerId, long index) {
        synchronized (peerWaterMarksByTerm) {
            checkTermForWaterMark(term, "updatePeerWaterMark");
            if (peerWaterMarksByTerm.get(term).get(peerId) < index) {
                peerWaterMarksByTerm.get(term).put(peerId, index);
            }
        }
    }

    public long getPeerWaterMark(long term, String peerId) {
        synchronized (peerWaterMarksByTerm) {
            checkTermForWaterMark(term, "getPeerWaterMark");
            return peerWaterMarksByTerm.get(term).get(peerId);
        }
    }

    public boolean isPendingFull(long currTerm) {
        checkTermForPendingMap(currTerm, "isPendingFull");
        return pendingAppendResponsesByTerm.get(currTerm).size() > dLedgerConfig.getMaxPendingRequestsNum();
    }

    public CompletableFuture<AppendEntryResponse> waitAck(DLedgerEntry entry, boolean isBatchWait) {
        updatePeerWaterMark(entry.getTerm(), memberState.getSelfId(), entry.getIndex());
        if (memberState.getPeerMap().size() == 1) {
            AppendEntryResponse response = new AppendEntryResponse();
            response.setGroup(memberState.getGroup());
            response.setLeaderId(memberState.getSelfId());
            response.setIndex(entry.getIndex());
            response.setTerm(entry.getTerm());
            response.setPos(entry.getPos());
            if (isBatchWait) {
                return BatchAppendFuture.newCompletedFuture(entry.getPos(), response);
            }
            return AppendFuture.newCompletedFuture(entry.getPos(), response);
        } else {
            checkTermForPendingMap(entry.getTerm(), "waitAck");
            AppendFuture<AppendEntryResponse> future;
            if (isBatchWait) {
                future = new BatchAppendFuture<>(dLedgerConfig.getMaxWaitAckTimeMs());
            } else {
                future = new AppendFuture<>(dLedgerConfig.getMaxWaitAckTimeMs());
            }
            future.setPos(entry.getPos());
            CompletableFuture<AppendEntryResponse> old = pendingAppendResponsesByTerm.get(entry.getTerm()).put(entry.getIndex(), future);
            if (old != null) {
                logger.warn("[MONITOR] get old wait at index={}", entry.getIndex());
            }
            return future;
        }
    }

    @SuppressWarnings("unused")
    public void wakeUpDispatchers() {
        for (EntryDispatcher dispatcher : dispatcherMap.values()) {
            dispatcher.wakeup();
        }
    }

    /**
     * This thread will check the quorum index and complete the pending requests.
     * 日志追加ACK投票处理线程，当前节点为主节点时激活。
     */
    private class QuorumAckChecker extends ShutdownAbleThread {

        private long lastPrintWatermarkTimeMs = System.currentTimeMillis();
        private long lastCheckLeakTimeMs = System.currentTimeMillis();
        private long lastQuorumIndex = -1;

        public QuorumAckChecker(Logger logger) {
            super("QuorumAckChecker-" + memberState.getSelfId(), logger);
        }

        @Override
        public void doWork() {
            try {
                if (DLedgerUtils.elapsed(lastPrintWatermarkTimeMs) > 3000) {
                    logger.info("[{}][{}] term={} ledgerBegin={} ledgerEnd={} committed={} watermarks={}",
                            memberState.getSelfId(), memberState.getRole(), memberState.currTerm(), dLedgerStore.getLedgerBeginIndex(), dLedgerStore.getLedgerEndIndex(), dLedgerStore.getCommittedIndex(), JSON.toJSONString(peerWaterMarksByTerm));
                    lastPrintWatermarkTimeMs = System.currentTimeMillis();
                }
                if (!memberState.isLeader()) {
                    waitForRunning(1);
                    return;
                }
                long currTerm = memberState.currTerm();
                checkTermForPendingMap(currTerm, "QuorumAckChecker");
                checkTermForWaterMark(currTerm, "QuorumAckChecker");
                if (pendingAppendResponsesByTerm.size() > 1) {
                    for (Long term : pendingAppendResponsesByTerm.keySet()) {
                        if (term == currTerm) {
                            continue;
                        }
                        for (Map.Entry<Long, TimeoutFuture<AppendEntryResponse>> futureEntry : pendingAppendResponsesByTerm.get(term).entrySet()) {
                            AppendEntryResponse response = new AppendEntryResponse();
                            response.setGroup(memberState.getGroup());
                            response.setIndex(futureEntry.getKey());
                            response.setCode(DLedgerResponseCode.TERM_CHANGED.getCode());
                            response.setLeaderId(memberState.getLeaderId());
                            logger.info("[TermChange] Will clear the pending response index={} for term changed from {} to {}", futureEntry.getKey(), term, currTerm);
                            futureEntry.getValue().complete(response);
                        }
                        pendingAppendResponsesByTerm.remove(term);
                    }
                }
                if (peerWaterMarksByTerm.size() > 1) {
                    for (Long term : peerWaterMarksByTerm.keySet()) {
                        if (term == currTerm) {
                            continue;
                        }
                        logger.info("[TermChange] Will clear the watermarks for term changed from {} to {}", term, currTerm);
                        peerWaterMarksByTerm.remove(term);
                    }
                }
                Map<String, Long> peerWaterMarks = peerWaterMarksByTerm.get(currTerm);

                List<Long> sortedWaterMarks = peerWaterMarks.values()
                        .stream()
                        .sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList());
                long quorumIndex = sortedWaterMarks.get(sortedWaterMarks.size() / 2);
                dLedgerStore.updateCommittedIndex(currTerm, quorumIndex);
                ConcurrentMap<Long, TimeoutFuture<AppendEntryResponse>> responses = pendingAppendResponsesByTerm.get(currTerm);
                boolean needCheck = false;
                int ackNum = 0;
                for (long i = quorumIndex; i > lastQuorumIndex; i--) {
                    try {
                        CompletableFuture<AppendEntryResponse> future = responses.remove(i);
                        if (future == null) {
                            needCheck = true;
                            break;
                        } else if (!future.isDone()) {
                            AppendEntryResponse response = new AppendEntryResponse();
                            response.setGroup(memberState.getGroup());
                            response.setTerm(currTerm);
                            response.setIndex(i);
                            response.setLeaderId(memberState.getSelfId());
                            response.setPos(((AppendFuture<AppendEntryResponse>) future).getPos());
                            future.complete(response);
                        }
                        ackNum++;
                    } catch (Throwable t) {
                        logger.error("Error in ack to index={} term={}", i, currTerm, t);
                    }
                }

                if (ackNum == 0) {
                    for (long i = quorumIndex + 1; i < Integer.MAX_VALUE; i++) {
                        TimeoutFuture<AppendEntryResponse> future = responses.get(i);
                        if (future == null) {
                            break;
                        } else if (future.isTimeOut()) {
                            AppendEntryResponse response = new AppendEntryResponse();
                            response.setGroup(memberState.getGroup());
                            response.setCode(DLedgerResponseCode.WAIT_QUORUM_ACK_TIMEOUT.getCode());
                            response.setTerm(currTerm);
                            response.setIndex(i);
                            response.setLeaderId(memberState.getSelfId());
                            future.complete(response);
                        } else {
                            break;
                        }
                    }
                    waitForRunning(1);
                }

                if (DLedgerUtils.elapsed(lastCheckLeakTimeMs) > 1000 || needCheck) {
                    updatePeerWaterMark(currTerm, memberState.getSelfId(), dLedgerStore.getLedgerEndIndex());
                    for (Map.Entry<Long, TimeoutFuture<AppendEntryResponse>> futureEntry : responses.entrySet()) {
                        if (futureEntry.getKey() < quorumIndex) {
                            AppendEntryResponse response = new AppendEntryResponse();
                            response.setGroup(memberState.getGroup());
                            response.setTerm(currTerm);
                            response.setIndex(futureEntry.getKey());
                            response.setLeaderId(memberState.getSelfId());
                            response.setPos(((AppendFuture) futureEntry.getValue()).getPos());
                            futureEntry.getValue().complete(response);
                            responses.remove(futureEntry.getKey());
                        }
                    }
                    lastCheckLeakTimeMs = System.currentTimeMillis();
                }
                lastQuorumIndex = quorumIndex;
            } catch (Throwable t) {
                DLedgerEntryPusher.logger.error("Error in {}", getName(), t);
                DLedgerUtils.sleep(100);
            }
        }
    }

    /**
     * 日志转发线程，当前节点为主节点时追加。
     * <p/>
     * This thread will be activated by the leader.
     * This thread will push the entry to follower(identified by peerId) and update the completed pushed index to index map.
     * <p>
     * Should generate a single thread for each peer.
     * <p>
     * The push has 4 types:
     * APPEND : append the entries to the follower
     * COMPARE : if the leader changes, the new leader should compare its entries to follower's
     * TRUNCATE : if the leader finished comparing by an index, the leader will send a request to truncate the follower's ledger
     * COMMIT: usually, the leader will attach the committed index with the APPEND request, but if the append requests are few and scattered,
     * the leader will send a pure request to inform the follower of committed index.
     * <p>
     * The common transferring between these types are as following:
     * <p>
     * COMPARE ---- TRUNCATE ---- APPEND ---- COMMIT
     * ^                             |
     * |---<-----<------<-------<----|
     */
    private class EntryDispatcher extends ShutdownAbleThread {
        //
        private final AtomicReference<PushEntryRequest.Type> type = new AtomicReference<>(PushEntryRequest.Type.COMPARE);
        //
        private long lastPushCommitTimeMs = -1;
        //
        private final String peerId;
        //
        private long compareIndex = -1;
        //
        private long writeIndex = -1;
        //
        private final int maxPendingSize = 1000;
        //
        private long term = -1;
        //
        private String leaderId = null;
        //
        private long lastCheckLeakTimeMs = System.currentTimeMillis();
        //
        private final ConcurrentMap<Long, Long> pendingMap = new ConcurrentHashMap<>();
        //
        private final ConcurrentMap<Long, Pair<Long, Integer>> batchPendingMap = new ConcurrentHashMap<>();
        //
        private final PushEntryRequest batchAppendEntryRequest = new PushEntryRequest();
        //
        private final Quota quota = new Quota(dLedgerConfig.getPeerPushQuota());

        public EntryDispatcher(String peerId, Logger logger) {
            super("EntryDispatcher-" + memberState.getSelfId() + "-" + peerId, logger);
            this.peerId = peerId;
        }

        //
        private boolean checkAndFreshState() {
            //如果节点的状态不是主节点，则直接返回 false。则结束 本次 doWork 方法。
            //因为只有主节点才需要向从节点转发日志。
            if (!memberState.isLeader()) {
                return false;
            }
            //如果当前节点状态是主节点，但当前的投票轮次与状态机轮次或 leaderId 还未设置，或 leaderId 与状态机的 leaderId 不相等，
            //这种情况通常是集群触发了重新选举，设置其term、leaderId与状态机同步，即将发送COMPARE 请求。
            if (term != memberState.currTerm() || leaderId == null || !leaderId.equals(memberState.getLeaderId())) {
                //加锁.
                synchronized (memberState) {
                    //再校验.
                    if (!memberState.isLeader()) {
                        return false;
                    }
                    //
                    PreConditions.check(memberState.getSelfId().equals(memberState.getLeaderId()), DLedgerResponseCode.UNKNOWN);
                    //
                    term = memberState.currTerm();
                    leaderId = memberState.getSelfId();
                    //
                    changeState(-1, PushEntryRequest.Type.COMPARE);
                }
            }
            return true;
        }

        //DLedger 节点所属组、从节点 id、主节点 id，当前投票轮次、日志内容、请求类型与 committedIndex(主节点已提交日志序号)。
        private PushEntryRequest buildPushRequest(DLedgerEntry entry, PushEntryRequest.Type target) {
            PushEntryRequest request = new PushEntryRequest();
            request.setGroup(memberState.getGroup());
            request.setRemoteId(peerId);
            request.setLeaderId(leaderId);
            request.setTerm(term);
            request.setEntry(entry);
            request.setType(target);
            request.setCommitIndex(dLedgerStore.getCommittedIndex());
            return request;
        }

        private void resetBatchAppendEntryRequest() {
            batchAppendEntryRequest.setGroup(memberState.getGroup());
            batchAppendEntryRequest.setRemoteId(peerId);
            batchAppendEntryRequest.setLeaderId(leaderId);
            batchAppendEntryRequest.setTerm(term);
            batchAppendEntryRequest.setType(PushEntryRequest.Type.APPEND);
            batchAppendEntryRequest.clear();
        }

        private void checkQuotaAndWait(DLedgerEntry entry) {
            if (dLedgerStore.getLedgerEndIndex() - entry.getIndex() <= maxPendingSize) {
                return;
            }
            if (dLedgerStore instanceof DLedgerMemoryStore) {
                return;
            }
            DLedgerMmapFileStore mmapFileStore = (DLedgerMmapFileStore) dLedgerStore;
            if (mmapFileStore.getDataFileList().getMaxWrotePosition() - entry.getPos() < dLedgerConfig.getPeerPushThrottlePoint()) {
                return;
            }
            quota.sample(entry.getSize());
            if (quota.validateNow()) {
                long leftNow = quota.leftNow();
                logger.warn("[Push-{}]Quota exhaust, will sleep {}ms", peerId, leftNow);
                DLedgerUtils.sleep(leftNow);
            }
        }

        private void doAppendInner(long index) throws Exception {
            //首先根据序号查询出日志。
            DLedgerEntry entry = getDLedgerEntryForAppend(index);
            if (null == entry) {
                return;
            }
            //检测配额，如果超过配额，会进行一定的限流，其关键实现点：
            //1.首先触发条件：append 挂起请求数已超过最大允许挂起数；基于文件存储并主从差异超过300m，可通过 peerPushThrottlePoint 配置。
            //2.每秒追加的日志超过 20m(可通过 peerPushQuota 配置)，则会 sleep 1s中后再追加。
            checkQuotaAndWait(entry);
            //构建 PUSH  请求日志。
            PushEntryRequest request = buildPushRequest(entry, PushEntryRequest.Type.APPEND);
            //通过 Netty 发送网络请求到从节点，从节点收到请求会进行处理
            CompletableFuture<PushEntryResponse> responseFuture = dLedgerRpcService.push(request);
            //用 pendingMap 记录待追加的日志的发送时间，用于发送端判断是否超时的一个依据。
            pendingMap.put(index, System.currentTimeMillis());
            //请求成功的处理逻辑，其关键实现点如下：
            //1.移除 pendingMap 中的关于该日志的发送超时时间。
            //2.更新已成功追加的日志序号(按投票轮次组织，并且每个从服务器一个键值对)。
            //3.唤醒 quorumAckChecker 线程(主要用于仲裁 append 结果)，后续会详细介绍。
            responseFuture.whenComplete((x, ex) -> {
                try {
                    PreConditions.check(ex == null, DLedgerResponseCode.UNKNOWN);
                    DLedgerResponseCode responseCode = DLedgerResponseCode.valueOf(x.getCode());
                    switch (responseCode) {
                        case SUCCESS:
                            pendingMap.remove(x.getIndex());
                            updatePeerWaterMark(x.getTerm(), peerId, x.getIndex());
                            quorumAckChecker.wakeup();
                            break;
                        //Push 请求出现状态不一致情况，将发送 COMPARE 请求，来对比主从节点的数据是否一致。
                        case INCONSISTENT_STATE:
                            logger.info("[Push-{}]Get INCONSISTENT_STATE when push index={} term={}", peerId, x.getIndex(), x.getTerm());
                            changeState(-1, PushEntryRequest.Type.COMPARE);
                            break;
                        default:
                            logger.warn("[Push-{}]Get error response code {} {}", peerId, responseCode, x.baseInfo());
                            break;
                    }
                } catch (Throwable t) {
                    logger.error("", t);
                }
            });
            lastPushCommitTimeMs = System.currentTimeMillis();
        }

        private DLedgerEntry getDLedgerEntryForAppend(long index) {
            DLedgerEntry entry;
            try {
                entry = dLedgerStore.get(index);
            } catch (DLedgerException e) {
                //  Do compare, in case the ledgerBeginIndex get refreshed.
                if (DLedgerResponseCode.INDEX_LESS_THAN_LOCAL_BEGIN.equals(e.getCode())) {
                    logger.info("[Push-{}]Get INDEX_LESS_THAN_LOCAL_BEGIN when requested index is {}, try to compare", peerId, index);
                    changeState(-1, PushEntryRequest.Type.COMPARE);
                    return null;
                }
                throw e;
            }
            PreConditions.check(entry != null, DLedgerResponseCode.UNKNOWN, "writeIndex=%d", index);
            return entry;
        }

        private void doCommit() throws Exception {
            //如果上一次单独发送 commit 的请求时间与当前时间相隔低于 1s，放弃本次提交请求。
            if (DLedgerUtils.elapsed(lastPushCommitTimeMs) > 1000) {
                //构建提交请求。
                PushEntryRequest request = buildPushRequest(null, PushEntryRequest.Type.COMMIT);
                //通过网络向从节点发送 commit 请求。
                dLedgerRpcService.push(request);
                lastPushCommitTimeMs = System.currentTimeMillis();
            }
        }

        private void doCheckAppendResponse() throws Exception {
            long peerWaterMark = getPeerWaterMark(term, peerId);
            Long sendTimeMs = pendingMap.get(peerWaterMark + 1);
            if (sendTimeMs != null && System.currentTimeMillis() - sendTimeMs > dLedgerConfig.getMaxPushTimeOutMs()) {
                logger.warn("[Push-{}]Retry to push entry at {}", peerId, peerWaterMark + 1);
                doAppendInner(peerWaterMark + 1);
            }
        }

        private void doAppend() throws Exception {
            while (true) {
                //
                if (!checkAndFreshState()) {
                    break;
                }
                //
                if (type.get() != PushEntryRequest.Type.APPEND) {
                    break;
                }
                //writeIndex 表示当前追加到从该节点的序号，通常情况下主节点向从节点发送 append 请求时，会附带主节点的已提交指针，
                //但如果append 请求发不那么频繁，writeIndex 大于 leaderEndIndex 时（由于pending请求超过其 pending 请求的队列长度（默认为1w)，时，
                //会阻止数据的追加，此时有可能出现 writeIndex 大于 leaderEndIndex 的情况，此时单独发送 COMMIT 请求。
                if (writeIndex > dLedgerStore.getLedgerEndIndex()) {
                    doCommit();
                    doCheckAppendResponse();
                    break;
                }
                //检测 pendingMap(挂起的请求数量)是否发送泄漏，即挂起队列中容量是否超过允许的最大挂起阀值。
                //获取当前节点关于本轮次的当前水位线(已成功 append 请求的日志序号)，如果发现正在挂起请求的日志序号小于水位线，则丢弃。
                if (pendingMap.size() >= maxPendingSize || (DLedgerUtils.elapsed(lastCheckLeakTimeMs) > 1000)) {
                    //
                    long peerWaterMark = getPeerWaterMark(term, peerId);
                    //
                    for (Long index : pendingMap.keySet()) {
                        if (index < peerWaterMark) {
                            pendingMap.remove(index);
                        }
                    }
                    lastCheckLeakTimeMs = System.currentTimeMillis();
                }
                //如果挂起的请求（等待从节点追加结果）大于 maxPendingSize 时，检查并追加一次 append 请求。
                if (pendingMap.size() >= maxPendingSize) {
                    doCheckAppendResponse();
                    break;
                }
                //具体的追加请求。
                doAppendInner(writeIndex);
                writeIndex++;
            }
        }

        private void sendBatchAppendEntryRequest() throws Exception {
            batchAppendEntryRequest.setCommitIndex(dLedgerStore.getCommittedIndex());
            CompletableFuture<PushEntryResponse> responseFuture = dLedgerRpcService.push(batchAppendEntryRequest);
            batchPendingMap.put(batchAppendEntryRequest.getFirstEntryIndex(), new Pair<>(System.currentTimeMillis(), batchAppendEntryRequest.getCount()));
            responseFuture.whenComplete((x, ex) -> {
                try {
                    PreConditions.check(ex == null, DLedgerResponseCode.UNKNOWN);
                    DLedgerResponseCode responseCode = DLedgerResponseCode.valueOf(x.getCode());
                    switch (responseCode) {
                        case SUCCESS:
                            batchPendingMap.remove(x.getIndex());
                            updatePeerWaterMark(x.getTerm(), peerId, x.getIndex());
                            break;
                        case INCONSISTENT_STATE:
                            logger.info("[Push-{}]Get INCONSISTENT_STATE when batch push index={} term={}", peerId, x.getIndex(), x.getTerm());
                            changeState(-1, PushEntryRequest.Type.COMPARE);
                            break;
                        default:
                            logger.warn("[Push-{}]Get error response code {} {}", peerId, responseCode, x.baseInfo());
                            break;
                    }
                } catch (Throwable t) {
                    logger.error("", t);
                }
            });
            lastPushCommitTimeMs = System.currentTimeMillis();
            batchAppendEntryRequest.clear();
        }

        private void doBatchAppendInner(long index) throws Exception {
            DLedgerEntry entry = getDLedgerEntryForAppend(index);
            if (null == entry) {
                return;
            }
            batchAppendEntryRequest.addEntry(entry);
            if (batchAppendEntryRequest.getTotalSize() >= dLedgerConfig.getMaxBatchPushSize()) {
                sendBatchAppendEntryRequest();
            }
        }

        private void doCheckBatchAppendResponse() throws Exception {
            long peerWaterMark = getPeerWaterMark(term, peerId);
            Pair<Long, Integer> pair = batchPendingMap.get(peerWaterMark + 1);
            if (pair != null && System.currentTimeMillis() - pair.getKey() > dLedgerConfig.getMaxPushTimeOutMs()) {
                long firstIndex = peerWaterMark + 1;
                long lastIndex = firstIndex + pair.getValue() - 1;
                logger.warn("[Push-{}]Retry to push entry from {} to {}", peerId, firstIndex, lastIndex);
                batchAppendEntryRequest.clear();
                for (long i = firstIndex; i <= lastIndex; i++) {
                    DLedgerEntry entry = dLedgerStore.get(i);
                    batchAppendEntryRequest.addEntry(entry);
                }
                sendBatchAppendEntryRequest();
            }
        }

        private void doBatchAppend() throws Exception {
            while (true) {
                if (!checkAndFreshState()) {
                    break;
                }
                if (type.get() != PushEntryRequest.Type.APPEND) {
                    break;
                }
                if (writeIndex > dLedgerStore.getLedgerEndIndex()) {
                    if (batchAppendEntryRequest.getCount() > 0) {
                        sendBatchAppendEntryRequest();
                    }
                    doCommit();
                    doCheckBatchAppendResponse();
                    break;
                }
                if (batchPendingMap.size() >= maxPendingSize || (DLedgerUtils.elapsed(lastCheckLeakTimeMs) > 1000)) {
                    long peerWaterMark = getPeerWaterMark(term, peerId);
                    for (Map.Entry<Long, Pair<Long, Integer>> entry : batchPendingMap.entrySet()) {
                        if (entry.getKey() + entry.getValue().getValue() - 1 <= peerWaterMark) {
                            batchPendingMap.remove(entry.getKey());
                        }
                    }
                    lastCheckLeakTimeMs = System.currentTimeMillis();
                }
                if (batchPendingMap.size() >= maxPendingSize) {
                    doCheckBatchAppendResponse();
                    break;
                }
                doBatchAppendInner(writeIndex);
                writeIndex++;
            }
        }

        private void doTruncate(long truncateIndex) throws Exception {
            PreConditions.check(type.get() == PushEntryRequest.Type.TRUNCATE, DLedgerResponseCode.UNKNOWN);
            DLedgerEntry truncateEntry = dLedgerStore.get(truncateIndex);
            PreConditions.check(truncateEntry != null, DLedgerResponseCode.UNKNOWN);
            logger.info("[Push-{}]Will push data to truncate truncateIndex={} pos={}", peerId, truncateIndex, truncateEntry.getPos());
            PushEntryRequest truncateRequest = buildPushRequest(truncateEntry, PushEntryRequest.Type.TRUNCATE);
            PushEntryResponse truncateResponse = dLedgerRpcService.push(truncateRequest).get(3, TimeUnit.SECONDS);
            PreConditions.check(truncateResponse != null, DLedgerResponseCode.UNKNOWN, "truncateIndex=%d", truncateIndex);
            PreConditions.check(truncateResponse.getCode() == DLedgerResponseCode.SUCCESS.getCode(), DLedgerResponseCode.valueOf(truncateResponse.getCode()), "truncateIndex=%d", truncateIndex);
            lastPushCommitTimeMs = System.currentTimeMillis();
            changeState(truncateIndex, PushEntryRequest.Type.APPEND);
        }

        private synchronized void changeState(long index, PushEntryRequest.Type target) {
            logger.info("[Push-{}]Change state from {} to {} at {}", peerId, type.get(), target, index);
            switch (target) {
                //如果将目标类型设置为 append，则重置 compareIndex ，并设置 writeIndex 为当前 index 加1。
                case APPEND:
                    compareIndex = -1;
                    updatePeerWaterMark(term, peerId, index);
                    quorumAckChecker.wakeup();
                    writeIndex = index + 1;
                    if (dLedgerConfig.isEnableBatchPush()) {
                        resetBatchAppendEntryRequest();
                    }
                    break;
                //如果将目标类型设置为 COMPARE，则重置 compareIndex 为负一，接下将向各个从节点发送 COMPARE 请求类似，并清除已挂起的请求。
                case COMPARE:
                    if (this.type.compareAndSet(PushEntryRequest.Type.APPEND, PushEntryRequest.Type.COMPARE)) {
                        compareIndex = -1;
                        if (dLedgerConfig.isEnableBatchPush()) {
                            batchPendingMap.clear();
                        } else {
                            pendingMap.clear();
                        }
                    }
                    break;
                //如果将目标类型设置为 TRUNCATE，则重置 compareIndex 为负一。
                case TRUNCATE:
                    compareIndex = -1;
                    break;
                default:
                    break;
            }
            type.set(target);
        }

        private void doCompare() throws Exception {
            while (true) {
                if (!checkAndFreshState()) {
                    break;
                }
                if (type.get() != PushEntryRequest.Type.COMPARE
                        && type.get() != PushEntryRequest.Type.TRUNCATE) {
                    break;
                }
                //如果已比较索引 和 ledgerEndIndex 都为 -1 ，表示一个新的 DLedger 集群，则直接跳出。
                if (compareIndex == -1 && dLedgerStore.getLedgerEndIndex() == -1) {
                    break;
                }
                //revise the compareIndex
                if (compareIndex == -1) {
                    compareIndex = dLedgerStore.getLedgerEndIndex();
                    logger.info("[Push-{}][DoCompare] compareIndex=-1 means start to compare", peerId);
                } else if (compareIndex > dLedgerStore.getLedgerEndIndex() || compareIndex < dLedgerStore.getLedgerBeginIndex()) {
                    logger.info("[Push-{}][DoCompare] compareIndex={} out of range {}-{}", peerId, compareIndex, dLedgerStore.getLedgerBeginIndex(), dLedgerStore.getLedgerEndIndex());
                    compareIndex = dLedgerStore.getLedgerEndIndex();
                }

                DLedgerEntry entry = dLedgerStore.get(compareIndex);
                PreConditions.check(entry != null, DLedgerResponseCode.INTERNAL_ERROR, "compareIndex=%d", compareIndex);
                PushEntryRequest request = buildPushRequest(entry, PushEntryRequest.Type.COMPARE);
                CompletableFuture<PushEntryResponse> responseFuture = dLedgerRpcService.push(request);
                PushEntryResponse response = responseFuture.get(3, TimeUnit.SECONDS);
                PreConditions.check(response != null, DLedgerResponseCode.INTERNAL_ERROR, "compareIndex=%d", compareIndex);
                PreConditions.check(response.getCode() == DLedgerResponseCode.INCONSISTENT_STATE.getCode() || response.getCode() == DLedgerResponseCode.SUCCESS.getCode()
                        , DLedgerResponseCode.valueOf(response.getCode()), "compareIndex=%d", compareIndex);
                long truncateIndex = -1;
                //根据响应结果计算需要截断的日志序号，其主要实现关键点如下：
                if (response.getCode() == DLedgerResponseCode.SUCCESS.getCode()) {
                    /*
                     * The comparison is successful:
                     * 1.Just change to append state, if the follower's end index is equal the compared index.
                     * 2.Truncate the follower, if the follower has some dirty entries.
                     */
                    //1.如果两者的日志序号相同，则无需截断，下次将直接先从节点发送 append 请求；否则将 truncateIndex  设置为响应结果中的 endIndex。
                    if (compareIndex == response.getEndIndex()) {
                        changeState(compareIndex, PushEntryRequest.Type.APPEND);
                        break;
                    } else {
                        truncateIndex = compareIndex;
                    }
                    //2.如果从节点存储的最大日志序号小于主节点的最小序号，或者从节点的最小日志序号大于主节点的最大日志序号，即两者不相交，
                    //这通常发生在从节点崩溃很长一段时间，而主节点删除了过期的条目时。truncateIndex 设置为主节点的 ledgerBeginIndex，即主节点目前最小的偏移量。
                } else if (response.getEndIndex() < dLedgerStore.getLedgerBeginIndex()
                        || response.getBeginIndex() > dLedgerStore.getLedgerEndIndex()) {
                    /*
                     The follower's entries does not intersect with the leader.
                     This usually happened when the follower has crashed for a long time while the leader has deleted the expired entries.
                     Just truncate the follower.
                     */
                    truncateIndex = dLedgerStore.getLedgerBeginIndex();
                    //3.如果已比较的日志序号小于从节点的开始日志序号，很可能是从节点磁盘发送损耗，从主节点最小日志序号开始同步。
                } else if (compareIndex < response.getBeginIndex()) {
                    /*
                     The compared index is smaller than the follower's begin index.
                     This happened rarely, usually means some disk damage.
                     Just truncate the follower.
                     */
                    truncateIndex = dLedgerStore.getLedgerBeginIndex();
                    //4.如果已比较的日志序号大于从节点的最大日志序号，则已比较索引设置为从节点最大的日志序号，触发数据的继续同步
                } else if (compareIndex > response.getEndIndex()) {
                    /*
                     The compared index is bigger than the follower's end index.
                     This happened frequently. For the compared index is usually starting from the end index of the leader.
                     */
                    compareIndex = response.getEndIndex();
                } else {//如果已比较的日志序号大于从节点的开始日志序号，但小于从节点的最大日志序号，则待比较索引减一。
                    /*
                      Compare failed and the compared index is in the range of follower's entries.
                     */
                    compareIndex--;
                }
                /*
                 The compared index is smaller than the leader's begin index, truncate the follower.
                 如果比较出来的日志序号小于主节点的最小日志需要，则设置为主节点的最小序号。
                 */
                if (compareIndex < dLedgerStore.getLedgerBeginIndex()) {
                    truncateIndex = dLedgerStore.getLedgerBeginIndex();
                }
                /*
                 If get value for truncateIndex, do it right now.
                 */
                if (truncateIndex != -1) {
                    changeState(truncateIndex, PushEntryRequest.Type.TRUNCATE);
                    doTruncate(truncateIndex);
                    break;
                }
            }
        }

        @Override
        public void doWork() {
            try {
                //检查状态，是否可以继续发送 append 或 compare。
                if (!checkAndFreshState()) {
                    waitForRunning(1);
                    return;
                }
                //如果推送类型为APPEND，主节点向从节点传播消息请求。
                if (type.get() == PushEntryRequest.Type.APPEND) {
                    if (dLedgerConfig.isEnableBatchPush()) {
                        doBatchAppend();
                    } else {
                        doAppend();
                    }
                } else {
                    doCompare();
                }
                Thread.yield();
            } catch (Throwable t) {
                DLedgerEntryPusher.logger.error("[Push-{}]Error in {} writeIndex={} compareIndex={}", peerId, getName(), writeIndex, compareIndex, t);
                changeState(-1, PushEntryRequest.Type.COMPARE);
                DLedgerUtils.sleep(500);
            }
        }
    }

    /**
     * 日志接收处理线程，当节点为从节点时激活。
     * This thread will be activated by the follower.
     * Accept the push request and order it by the index, then append to ledger store one by one.
     */
    private class EntryHandler extends ShutdownAbleThread {
        //上一次检查主服务器是否有 push 消息的时间戳。
        private long lastCheckFastForwardTimeMs = System.currentTimeMillis();
        //append 请求处理队列。
        ConcurrentMap<Long, Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>> writeRequestMap = new ConcurrentHashMap<>();
        //COMMIT、COMPARE、TRUNCATE 相关请求
        BlockingQueue<Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>> compareOrTruncateRequests = new ArrayBlockingQueue<>(100);

        public EntryHandler(Logger logger) {
            super("EntryHandler-" + memberState.getSelfId(), logger);
        }

        //
        public CompletableFuture<PushEntryResponse> handlePush(PushEntryRequest request) throws Exception {
            //The timeout should smaller than the remoting layer's request timeout
            CompletableFuture<PushEntryResponse> future = new TimeoutFuture<>(1000);
            switch (request.getType()) {
                case APPEND:
                    if (request.isBatch()) {
                        PreConditions.check(request.getBatchEntry() != null && request.getCount() > 0, DLedgerResponseCode.UNEXPECTED_ARGUMENT);
                    } else {
                        PreConditions.check(request.getEntry() != null, DLedgerResponseCode.UNEXPECTED_ARGUMENT);
                    }
                    long index = request.getFirstEntryIndex();
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> old = writeRequestMap.putIfAbsent(index, new Pair<>(request, future));
                    if (old != null) {
                        logger.warn("[MONITOR]The index {} has already existed with {} and curr is {}", index, old.getKey().baseInfo(), request.baseInfo());
                        future.complete(buildResponse(request, DLedgerResponseCode.REPEATED_PUSH.getCode()));
                    }
                    break;
                case COMMIT:
                    compareOrTruncateRequests.put(new Pair<>(request, future));
                    break;
                case COMPARE:
                case TRUNCATE:
                    PreConditions.check(request.getEntry() != null, DLedgerResponseCode.UNEXPECTED_ARGUMENT);
                    //清空
                    writeRequestMap.clear();
                    //s
                    compareOrTruncateRequests.put(new Pair<>(request, future));
                    break;
                default:
                    logger.error("[BUG]Unknown type {} from {}", request.getType(), request.baseInfo());
                    future.complete(buildResponse(request, DLedgerResponseCode.UNEXPECTED_ARGUMENT.getCode()));
                    break;
            }
            wakeup();
            return future;
        }

        private PushEntryResponse buildResponse(PushEntryRequest request, int code) {
            PushEntryResponse response = new PushEntryResponse();
            response.setGroup(request.getGroup());
            response.setCode(code);
            response.setTerm(request.getTerm());
            if (request.getType() != PushEntryRequest.Type.COMMIT) {
                response.setIndex(request.getEntry().getIndex());
            }
            response.setBeginIndex(dLedgerStore.getLedgerBeginIndex());
            response.setEndIndex(dLedgerStore.getLedgerEndIndex());
            return response;
        }

        private PushEntryResponse buildBatchAppendResponse(PushEntryRequest request, int code) {
            PushEntryResponse response = new PushEntryResponse();
            response.setGroup(request.getGroup());
            response.setCode(code);
            response.setTerm(request.getTerm());
            response.setIndex(request.getLastEntryIndex());
            response.setBeginIndex(dLedgerStore.getLedgerBeginIndex());
            response.setEndIndex(dLedgerStore.getLedgerEndIndex());
            return response;
        }

        //
        private void handleDoAppend(long writeIndex, PushEntryRequest request,
                                    CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(writeIndex == request.getEntry().getIndex(), DLedgerResponseCode.INCONSISTENT_STATE);
                //
                DLedgerEntry entry = dLedgerStore.appendAsFollower(request.getEntry(), request.getTerm(), request.getLeaderId());
                //
                PreConditions.check(entry.getIndex() == writeIndex, DLedgerResponseCode.INCONSISTENT_STATE);
                //
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
                //更新index.
                dLedgerStore.updateCommittedIndex(request.getTerm(), request.getCommitIndex());
            } catch (Throwable t) {
                logger.error("[HandleDoWrite] writeIndex={}", writeIndex, t);
                future.complete(buildResponse(request, DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
            }
        }

        private CompletableFuture<PushEntryResponse> handleDoCompare(long compareIndex, PushEntryRequest request,
                                                                     CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(compareIndex == request.getEntry().getIndex(), DLedgerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.COMPARE, DLedgerResponseCode.UNKNOWN);
                DLedgerEntry local = dLedgerStore.get(compareIndex);
                PreConditions.check(request.getEntry().equals(local), DLedgerResponseCode.INCONSISTENT_STATE);
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
            } catch (Throwable t) {
                logger.error("[HandleDoCompare] compareIndex={}", compareIndex, t);
                future.complete(buildResponse(request, DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
            }
            return future;
        }

        private CompletableFuture<PushEntryResponse> handleDoCommit(long committedIndex, PushEntryRequest request,
                                                                    CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(committedIndex == request.getCommitIndex(), DLedgerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.COMMIT, DLedgerResponseCode.UNKNOWN);
                dLedgerStore.updateCommittedIndex(request.getTerm(), committedIndex);
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
            } catch (Throwable t) {
                logger.error("[HandleDoCommit] committedIndex={}", request.getCommitIndex(), t);
                future.complete(buildResponse(request, DLedgerResponseCode.UNKNOWN.getCode()));
            }
            return future;
        }

        //Truncate.
        private CompletableFuture<PushEntryResponse> handleDoTruncate(long truncateIndex, PushEntryRequest request,
                                                                      CompletableFuture<PushEntryResponse> future) {
            try {
                //
                logger.info("[HandleDoTruncate] truncateIndex={} pos={}", truncateIndex, request.getEntry().getPos());
                //不等报错.
                PreConditions.check(truncateIndex == request.getEntry().getIndex(), DLedgerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.TRUNCATE, DLedgerResponseCode.UNKNOWN);
                //这里截取.
                long index = dLedgerStore.truncate(request.getEntry(), request.getTerm(), request.getLeaderId());
                PreConditions.check(index == truncateIndex, DLedgerResponseCode.INCONSISTENT_STATE);
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
                dLedgerStore.updateCommittedIndex(request.getTerm(), request.getCommitIndex());
            } catch (Throwable t) {
                logger.error("[HandleDoTruncate] truncateIndex={}", truncateIndex, t);
                future.complete(buildResponse(request, DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
            }
            return future;
        }

        private void handleDoBatchAppend(long writeIndex, PushEntryRequest request,
                                         CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(writeIndex == request.getFirstEntryIndex(), DLedgerResponseCode.INCONSISTENT_STATE);
                for (DLedgerEntry entry : request.getBatchEntry()) {
                    dLedgerStore.appendAsFollower(entry, request.getTerm(), request.getLeaderId());
                }
                future.complete(buildBatchAppendResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
                dLedgerStore.updateCommittedIndex(request.getTerm(), request.getCommitIndex());
            } catch (Throwable t) {
                logger.error("[HandleDoBatchAppend]", t);
            }

        }

        private void checkAppendFuture(long endIndex) {
            long minFastForwardIndex = Long.MAX_VALUE;
            for (Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair : writeRequestMap.values()) {
                long firstEntryIndex = pair.getKey().getFirstEntryIndex();
                long lastEntryIndex = pair.getKey().getLastEntryIndex();
                //Fall behind
                if (lastEntryIndex <= endIndex) {
                    try {
                        if (pair.getKey().isBatch()) {
                            for (DLedgerEntry dLedgerEntry : pair.getKey().getBatchEntry()) {
                                PreConditions.check(dLedgerEntry.equals(dLedgerStore.get(dLedgerEntry.getIndex())), DLedgerResponseCode.INCONSISTENT_STATE);
                            }
                        } else {
                            DLedgerEntry dLedgerEntry = pair.getKey().getEntry();
                            PreConditions.check(dLedgerEntry.equals(dLedgerStore.get(dLedgerEntry.getIndex())), DLedgerResponseCode.INCONSISTENT_STATE);
                        }
                        pair.getValue().complete(buildBatchAppendResponse(pair.getKey(), DLedgerResponseCode.SUCCESS.getCode()));
                        logger.warn("[PushFallBehind]The leader pushed an batch append entry last index={} smaller than current ledgerEndIndex={}, maybe the last ack is missed", lastEntryIndex, endIndex);
                    } catch (Throwable t) {
                        logger.error("[PushFallBehind]The leader pushed an batch append entry last index={} smaller than current ledgerEndIndex={}, maybe the last ack is missed", lastEntryIndex, endIndex, t);
                        pair.getValue().complete(buildBatchAppendResponse(pair.getKey(), DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
                    }
                    writeRequestMap.remove(pair.getKey().getFirstEntryIndex());
                    continue;
                }
                if (firstEntryIndex == endIndex + 1) {
                    return;
                }
                TimeoutFuture<PushEntryResponse> future = (TimeoutFuture<PushEntryResponse>) pair.getValue();
                if (!future.isTimeOut()) {
                    continue;
                }
                if (firstEntryIndex < minFastForwardIndex) {
                    minFastForwardIndex = firstEntryIndex;
                }
            }
            if (minFastForwardIndex == Long.MAX_VALUE) {
                return;
            }
            Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair = writeRequestMap.get(minFastForwardIndex);
            if (pair == null) {
                return;
            }
            logger.warn("[PushFastForward] ledgerEndIndex={} entryIndex={}", endIndex, minFastForwardIndex);
            pair.getValue().complete(buildBatchAppendResponse(pair.getKey(), DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
        }

        /**
         * The leader does push entries to follower, and record the pushed index. But in the following conditions, the push may get stopped.
         * * If the follower is abnormally shutdown, its ledger end index may be smaller than before. At this time, the leader may push fast-forward entries, and retry all the time.
         * * If the last ack is missed, and no new message is coming in.The leader may retry push the last message, but the follower will ignore it.
         *
         * @param endIndex endIndex.
         */
        private void checkAbnormalFuture(long endIndex) {
            if (DLedgerUtils.elapsed(lastCheckFastForwardTimeMs) < 1000) {
                return;
            }
            lastCheckFastForwardTimeMs = System.currentTimeMillis();
            if (writeRequestMap.isEmpty()) {
                return;
            }

            checkAppendFuture(endIndex);
        }

        @Override
        public void doWork() {
            try {
                //不是follower.
                if (!memberState.isFollower()) {
                    waitForRunning(1);
                    return;
                }
                //compare和truncate的请求不为空
                if (compareOrTruncateRequests.peek() != null) {
                    //
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair = compareOrTruncateRequests.poll();
                    PreConditions.check(pair != null, DLedgerResponseCode.UNKNOWN);
                    switch (pair.getKey().getType()) {
                        case TRUNCATE:
                            handleDoTruncate(pair.getKey().getEntry().getIndex(), pair.getKey(), pair.getValue());
                            break;
                        case COMPARE:
                            handleDoCompare(pair.getKey().getEntry().getIndex(), pair.getKey(), pair.getValue());
                            break;
                        case COMMIT:
                            handleDoCommit(pair.getKey().getCommitIndex(), pair.getKey(), pair.getValue());
                            break;
                        default:
                            break;
                    }
                } else {
                    long nextIndex = dLedgerStore.getLedgerEndIndex() + 1;
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair = writeRequestMap.remove(nextIndex);
                    if (pair == null) {
                        checkAbnormalFuture(dLedgerStore.getLedgerEndIndex());
                        waitForRunning(1);
                        return;
                    }
                    PushEntryRequest request = pair.getKey();
                    if (request.isBatch()) {
                        handleDoBatchAppend(nextIndex, request, pair.getValue());
                    } else {
                        handleDoAppend(nextIndex, request, pair.getValue());
                    }
                }
            } catch (Throwable t) {
                DLedgerEntryPusher.logger.error("Error in {}", getName(), t);
                DLedgerUtils.sleep(100);
            }
        }
    }
}
