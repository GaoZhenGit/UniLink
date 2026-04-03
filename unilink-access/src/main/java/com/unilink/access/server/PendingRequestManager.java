package com.unilink.access.server;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingRequestManager {

    private static final Logger log = LoggerFactory.getLogger(PendingRequestManager.class);

    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    public void registerPendingRequest(String msgId, ChannelHandlerContext ctx) {
        registerPendingRequest(msgId, ctx, null);
    }

    public void registerPendingRequest(String msgId, ChannelHandlerContext ctx, String method) {
        pendingRequests.put(msgId, new PendingRequest(ctx, method));
        log.debug("注册待处理请求: msgId={}, method={}", msgId, method);
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
        log.debug("移除待处理请求: msgId={}", msgId);
    }

    public int getPendingCount() {
        return pendingRequests.size();
    }

    private static class PendingRequest {
        final ChannelHandlerContext ctx;
        final String method;
        volatile boolean headersSent = false;
        volatile int statusCode;
        volatile Map<String, String> headers;

        PendingRequest(ChannelHandlerContext ctx, String method) {
            this.ctx = ctx;
            this.method = method;
        }
    }
}
