package com.unilink.proxy.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class WorkerConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(WorkerConnectionManager.class);

    private final Set<Channel> workers = new CopyOnWriteArraySet<>();
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private int roundRobinIndex = 0;

    public void registerWorker(Channel channel) {
        workers.add(channel);
        log.info("Worker连接注册，当前Worker数量: {}", workers.size());
    }

    public void removeWorker(Channel channel) {
        workers.remove(channel);
        log.info("Worker断开，当前Worker数量: {}", workers.size());
    }

    public Channel getAvailableWorker() {
        if (workers.isEmpty()) {
            return null;
        }

        // 简单的 round-robin 负载均衡
        synchronized (this) {
            if (workers.isEmpty()) {
                return null;
            }
            int size = workers.size();
            Channel worker = null;
            int attempts = 0;
            while (attempts < size) {
                roundRobinIndex = (roundRobinIndex + 1) % size;
                worker = (Channel) workers.toArray()[roundRobinIndex];
                if (worker.isActive()) {
                    return worker;
                }
                attempts++;
            }
            return null;
        }
    }

    public void registerPendingRequest(String msgId, ChannelHandlerContext ctx) {
        registerPendingRequest(msgId, ctx, null);
    }

    public void registerPendingRequest(String msgId, ChannelHandlerContext ctx, String method) {
        pendingRequests.put(msgId, new PendingRequest(ctx, method));
    }

    public ChannelHandlerContext getPendingRequest(String msgId) {
        PendingRequest req = pendingRequests.get(msgId);
        return req != null ? req.ctx : null;
    }

    public String getPendingRequestMethod(String msgId) {
        PendingRequest req = pendingRequests.get(msgId);
        return req != null ? req.method : null;
    }

    public boolean isHeadersSent(String msgId) {
        PendingRequest req = pendingRequests.get(msgId);
        return req != null && req.headersSent;
    }

    public void setHeadersSent(String msgId, int statusCode, Map<String, String> headers) {
        PendingRequest req = pendingRequests.get(msgId);
        if (req != null) {
            req.headersSent = true;
            req.statusCode = statusCode;
            req.headers = headers;
        }
    }

    public void removePendingRequest(String msgId) {
        pendingRequests.remove(msgId);
    }

    public int getWorkerCount() {
        return workers.size();
    }

    private static class PendingRequest {
        final ChannelHandlerContext ctx;
        final String method;
        volatile boolean headersSent = false;  // 是否已发送响应头
        volatile int statusCode;  // 响应状态码
        volatile Map<String, String> headers;  // 响应头

        PendingRequest(ChannelHandlerContext ctx, String method) {
            this.ctx = ctx;
            this.method = method;
        }
    }
}