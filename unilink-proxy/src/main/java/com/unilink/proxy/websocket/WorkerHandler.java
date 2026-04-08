package com.unilink.proxy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.proxy.config.ProxyConfig;
import com.unilink.proxy.manager.AccessHistoryManager;
import com.unilink.proxy.manager.SessionRouter;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 处理 Worker 端的 WebSocket 连接
 * 使用 @Sharable 支持多连接共享
 */
@Component
@ChannelHandler.Sharable
public class WorkerHandler extends TextWebSocketFrameHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkerHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final AttributeKey<ScheduledExecutorService> HEARTBEAT_SCHEDULER =
            AttributeKey.valueOf("worker_heartbeat_scheduler");

    @Autowired
    private SessionRouter sessionRouter;

    @Autowired
    private ProxyConfig config;

    @Autowired
    private AccessHistoryManager historyManager;

    // 分片缓存：fragId -> FragContext
    private final Map<String, FragContext> fragCache = new ConcurrentHashMap<>();

    // 待处理的二进制分片对应的 fragId
    private final Map<String, String> pendingBinaryFragId = new ConcurrentHashMap<>();

    /**
     * 分片上下文
     */
    private static class FragContext {
        final String fragId;
        final int fragCount;
        final byte[][] fragments;  // 按 seqIdx 存储分片数据
        final String[] fragmentMetas;  // 按 seqIdx 存储分片元数据 JSON
        final String metadata;  // 原始元数据 JSON
        int receivedCount;
        long timestamp;

        FragContext(String fragId, int fragCount, String metadata) {
            this.fragId = fragId;
            this.fragCount = fragCount;
            this.metadata = metadata;
            this.fragments = new byte[fragCount][];
            this.fragmentMetas = new String[fragCount];
            this.receivedCount = 0;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Worker WebSocket连接建立: {}", ctx.channel().remoteAddress());
        // 不要在这里注册，等收到 register 消息再注册
        startHeartbeat(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Worker WebSocket连接断开: {}", ctx.channel().remoteAddress());
        sessionRouter.unregisterWorker(ctx.channel());
        stopHeartbeat(ctx);
    }

    @Override
    protected void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        log.debug("收到Worker消息: {}", text.length() > 200 ? text.substring(0, 200) + "..." : text);

        Map<String, Object> msg = objectMapper.readValue(text, Map.class);
        String type = (String) msg.get("type");

        // 检查是否是分片消息
        Integer fragCount = msg.get("fragCount") != null ? ((Number) msg.get("fragCount")).intValue() : 1;
        Integer seqIdx = msg.get("seqIdx") != null ? ((Number) msg.get("seqIdx")).intValue() : 0;
        String fragId = (String) msg.get("fragId");

        if (fragCount > 1 && fragId != null) {
            // 分片消息，缓存分片信息
            handleFragmentedText(ctx, text, msg, fragId, fragCount, seqIdx);
            return;
        }

        // 非分片消息，按原逻辑处理
        switch (type) {
            case "register":
                String workerId = (String) msg.get("workerId");
                sessionRouter.registerWorker(ctx.channel(), workerId);
                break;
            case "heartbeat":
                handleHeartbeat(ctx, msg);
                break;
            case "heartbeat_ack":
                log.debug("收到Worker心跳响应");
                break;
            case "http_response":
                // 记录访问历史（仅在 finished 时）
                String respMsgId = (String) msg.get("msgId");
                Boolean finished = msg.get("finished") != null ? (Boolean) msg.get("finished") : false;
                if (finished && respMsgId != null) {
                    Map<String, Object> requestInfo = sessionRouter.removePendingRequest(respMsgId);
                    if (requestInfo != null) {
                        String accessId = (String) requestInfo.get("accessId");
                        String url = (String) requestInfo.get("url");
                        int statusCode = msg.get("statusCode") != null ? ((Number) msg.get("statusCode")).intValue() : 200;
                        boolean success = statusCode >= 200 && statusCode < 400;
                        historyManager.recordAccess(accessId, url, statusCode, success);
                    }
                }
                // 标记下一帧二进制数据需要转发
                int respBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (respBodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toAccess");
                }
                // 转发到 access
                sessionRouter.forwardTextToAccess(ctx.channel(), text);
                break;
            case "http_chunk":
                // 在最后一个 chunk 时写入历史记录
                Boolean chunkFinished = msg.get("finished") != null ? (Boolean) msg.get("finished") : false;
                if (chunkFinished) {
                    String chunkMsgId = (String) msg.get("msgId");
                    if (chunkMsgId != null) {
                        Map<String, Object> requestInfo = sessionRouter.removePendingRequest(chunkMsgId);
                        if (requestInfo != null) {
                            String accessId = (String) requestInfo.get("accessId");
                            String url = (String) requestInfo.get("url");
                            int statusCode = msg.get("statusCode") != null ? ((Number) msg.get("statusCode")).intValue() : 200;
                            boolean success = statusCode >= 200 && statusCode < 400;
                            historyManager.recordAccess(accessId, url, statusCode, success);
                        }
                    }
                }
                // 标记下一帧二进制数据需要转发
                int chunkBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (chunkBodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toAccess");
                }
                // 转发到 access
                sessionRouter.forwardTextToAccess(ctx.channel(), text);
                break;
            case "tunnel_data":
                // 标记下一帧二进制数据需要转发
                int tunnelBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (tunnelBodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toAccess");
                }
                // 转发到 access
                sessionRouter.forwardTextToAccess(ctx.channel(), text);
                break;
            case "socks5_response":
                // SOCKS5 连接响应，转发到 access，同时记录历史
                String socks5RespMsgId = (String) msg.get("msgId");
                if (socks5RespMsgId != null) {
                    Map<String, Object> socks5ReqInfo = sessionRouter.removePendingRequest(socks5RespMsgId);
                    if (socks5ReqInfo != null) {
                        String accessId = (String) socks5ReqInfo.get("accessId");
                        String host = (String) socks5ReqInfo.get("host");
                        Integer port = socks5ReqInfo.get("port") != null ? (Integer) socks5ReqInfo.get("port") : 443;
                        int statusCode = msg.get("status") != null ? ((Number) msg.get("status")).intValue() : -1;
                        boolean success = statusCode == 0;
                        // SOCKS5 只知道 host:port，无法判断真实 scheme（http/https），直接拼接
                        String targetUrl = String.format("%s:%d", host, port);
                        historyManager.recordAccess(accessId, targetUrl, "SOCKS5", statusCode, success);
                    }
                }
                // 标记下一帧二进制数据需要转发
                int socks5RespBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (socks5RespBodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toAccess");
                }
                sessionRouter.forwardTextToAccess(ctx.channel(), text);
                break;
            case "socks5_tunnel_data":
                // SOCKS5 隧道数据，转发到 access
                int socks5TunnelBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (socks5TunnelBodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toAccess");
                }
                sessionRouter.forwardTextToAccess(ctx.channel(), text);
                break;
            default:
                // 其他消息直接转发
                sessionRouter.forwardTextToAccess(ctx.channel(), text);
        }
    }

    /**
     * 处理分片文本消息
     */
    private void handleFragmentedText(ChannelHandlerContext ctx, String text, Map<String, Object> msg,
                                      String fragId, int fragCount, int seqIdx) throws Exception {
        FragContext fragCtx = fragCache.get(fragId);
        if (fragCtx == null) {
            // 第一个分片，创建分片上下文
            fragCtx = new FragContext(fragId, fragCount, text);
            fragCache.put(fragId, fragCtx);
            log.debug("开始接收分片消息: fragId={}, fragCount={}", fragId, fragCount);
        }

        // 存储分片元数据（用于后续组装元数据）
        fragCtx.fragmentMetas[seqIdx] = text;

        // 设置待处理的 fragId（等待二进制分片）
        pendingBinaryFragId.put(ctx.channel().id().asShortText(), fragId);

        // 如果是第一个分片，设置 binary target
        if (seqIdx == 0) {
            sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toAccess");
        }

        log.debug("收到分片元数据: fragId={}, seqIdx={}/{}", fragId, seqIdx, fragCount);
    }

    @Override
    protected void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        byte[] body = new byte[frame.content().readableBytes()];
        frame.content().readBytes(body);

        String channelId = ctx.channel().id().asShortText();
        String fragId = pendingBinaryFragId.get(channelId);

        if (fragId != null) {
            // 处理分片二进制数据
            handleFragmentedBinary(ctx, body, fragId);
        } else {
            // 非分片消息，直接转发
            sessionRouter.forwardBinaryToAccess(ctx.channel(), body);
        }
    }

    /**
     * 处理分片二进制数据
     */
    private void handleFragmentedBinary(ChannelHandlerContext ctx, byte[] body, String fragId) throws Exception {
        FragContext fragCtx = fragCache.get(fragId);
        if (fragCtx == null) {
            log.warn("未找到分片上下文: fragId={}", fragId);
            sessionRouter.forwardBinaryToAccess(ctx.channel(), body);
            return;
        }

        String channelId = ctx.channel().id().asShortText();
        pendingBinaryFragId.remove(channelId);

        // 找到当前分片应该放置的位置
        int placedIdx = -1;
        for (int i = 0; i < fragCtx.fragCount; i++) {
            if (fragCtx.fragments[i] == null) {
                fragCtx.fragments[i] = body;
                placedIdx = i;
                fragCtx.receivedCount++;
                break;
            }
        }

        if (placedIdx == -1) {
            log.warn("分片已满，无法放置: fragId={}", fragId);
            return;
        }

        log.debug("收到分片数据: fragId={}, placedIdx={}/{}", fragId, placedIdx, fragCtx.fragCount);

        // 检查是否所有分片都已接收
        if (fragCtx.receivedCount == fragCtx.fragCount) {
            // 组装完整消息
            assembleAndForward(ctx, fragCtx);
            // 清理缓存
            fragCache.remove(fragId);
        }
    }

    /**
     * 组装分片消息并转发
     */
    private void assembleAndForward(ChannelHandlerContext ctx, FragContext fragCtx) throws Exception {
        // 计算总大小
        int totalSize = 0;
        for (int i = 0; i < fragCtx.fragCount; i++) {
            totalSize += fragCtx.fragments[i].length;
        }

        // 组装二进制数据
        byte[] assembledData = new byte[totalSize];
        int offset = 0;
        for (int i = 0; i < fragCtx.fragCount; i++) {
            System.arraycopy(fragCtx.fragments[i], 0, assembledData, offset, fragCtx.fragments[i].length);
            offset += fragCtx.fragments[i].length;
        }

        log.debug("组装分片消息完成: fragId={}, totalSize={}", fragCtx.fragId, totalSize);

        // 使用第一个分片的元数据（已经是最新的），移除分片标记后转发
        Map<String, Object> meta = objectMapper.readValue(fragCtx.metadata, Map.class);
        meta.remove("fragId");
        meta.remove("seqIdx");
        meta.remove("fragCount");

        String forwardedMeta = objectMapper.writeValueAsString(meta);
        sessionRouter.forwardTextToAccess(ctx.channel(), forwardedMeta);
        sessionRouter.forwardBinaryToAccess(ctx.channel(), assembledData);
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, Map<String, Object> msg) {
        try {
            Map<String, Object> ackMap = new HashMap<>();
            ackMap.put("type", "heartbeat_ack");
            ackMap.put("timestamp", System.currentTimeMillis());
            String ack = objectMapper.writeValueAsString(ackMap);
            ctx.channel().writeAndFlush(new TextWebSocketFrame(ack));
        } catch (Exception e) {
            log.error("发送心跳响应失败", e);
        }
    }

    private void startHeartbeat(ChannelHandlerContext ctx) {
        int interval = config.getWebsocket().getHeartbeatInterval();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ctx.channel().attr(HEARTBEAT_SCHEDULER).set(scheduler);

        scheduler.scheduleAtFixedRate(() -> {
            if (ctx.channel().isActive()) {
                try {
                    Map<String, Object> pingMap = new HashMap<>();
                    pingMap.put("type", "heartbeat");
                    pingMap.put("timestamp", System.currentTimeMillis());
                    String ping = objectMapper.writeValueAsString(pingMap);
                    ctx.channel().writeAndFlush(new TextWebSocketFrame(ping));
                    log.debug("发送心跳到Worker");
                } catch (Exception e) {
                    log.error("发送心跳失败", e);
                }
            }
            // 清理过期的分片缓存（超过 30 秒未完成）
            cleanupExpiredFragments();
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * 清理过期的分片缓存
     */
    private void cleanupExpiredFragments() {
        long now = System.currentTimeMillis();
        long maxAge = 30000; // 30 秒
        fragCache.entrySet().removeIf(entry -> {
            if (now - entry.getValue().timestamp > maxAge) {
                log.warn("清理过期分片: fragId={}, age={}ms", entry.getKey(), now - entry.getValue().timestamp);
                return true;
            }
            return false;
        });
    }

    private void stopHeartbeat(ChannelHandlerContext ctx) {
        Attribute<ScheduledExecutorService> attr = ctx.channel().attr(HEARTBEAT_SCHEDULER);
        ScheduledExecutorService scheduler = attr.get();
        if (scheduler != null) {
            scheduler.shutdown();
            attr.set(null);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Worker WebSocket处理器异常", cause);
        ctx.close();
    }
}
