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
 * 处理 Access 端的 WebSocket 连接
 * 使用 @Sharable 支持多连接共享
 */
@Component
@ChannelHandler.Sharable
public class AccessHandler extends TextWebSocketFrameHandler {

    private static final Logger log = LoggerFactory.getLogger(AccessHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final AttributeKey<ScheduledExecutorService> HEARTBEAT_SCHEDULER =
            AttributeKey.valueOf("access_heartbeat_scheduler");

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
        log.info("Access WebSocket连接建立: {}", ctx.channel().remoteAddress());
        // 不要在这里注册，等收到 register 消息再注册
        startHeartbeat(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Access WebSocket连接断开: {}", ctx.channel().remoteAddress());
        sessionRouter.unregisterAccess(ctx.channel());
        stopHeartbeat(ctx);
    }

    @Override
    protected void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        log.debug("收到Access消息: {}", text.length() > 200 ? text.substring(0, 200) + "..." : text);

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
                String accessId = (String) msg.get("accessId");
                sessionRouter.registerAccess(ctx.channel(), accessId);
                break;
            case "heartbeat":
                handleHeartbeat(ctx, msg);
                break;
            case "heartbeat_ack":
                log.debug("收到Access心跳响应");
                break;
            case "http_request":
                // 记录待处理请求，用于后续记录历史
                String msgId = (String) msg.get("msgId");
                String url = (String) msg.get("url");
                String reqAccessId = sessionRouter.getAccessIdByChannel(ctx.channel().id().asShortText());
                if (msgId != null && reqAccessId != null) {
                    Map<String, Object> requestInfo = new HashMap<>();
                    requestInfo.put("accessId", reqAccessId);
                    requestInfo.put("url", url);
                    requestInfo.put("timestamp", System.currentTimeMillis());
                    sessionRouter.addPendingRequest(msgId, requestInfo);
                }
                // 标记下一帧二进制数据需要转发
                int bodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (bodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toWorker");
                }
                // 转发到 worker
                sessionRouter.forwardTextToWorker(ctx.channel(), text);
                break;
            case "tunnel_data":
                // 标记下一帧二进制数据需要转发
                int tunnelBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (tunnelBodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toWorker");
                }
                // 转发到 worker
                sessionRouter.forwardTextToWorker(ctx.channel(), text);
                break;
            case "socks5_connect":
                // SOCKS5 连接请求，转发到 worker，同时保存待处理请求信息用于记录历史
                String socks5MsgId = (String) msg.get("msgId");
                String socks5Host = (String) msg.get("host");
                Integer socks5Port = msg.get("port") != null ? ((Number) msg.get("port")).intValue() : 443;
                String socks5AccessId = sessionRouter.getAccessIdByChannel(ctx.channel().id().asShortText());
                if (socks5MsgId != null && socks5AccessId != null) {
                    Map<String, Object> socks5RequestInfo = new HashMap<>();
                    socks5RequestInfo.put("accessId", socks5AccessId);
                    socks5RequestInfo.put("host", socks5Host);
                    socks5RequestInfo.put("port", socks5Port);
                    socks5RequestInfo.put("timestamp", System.currentTimeMillis());
                    sessionRouter.addPendingRequest(socks5MsgId, socks5RequestInfo);
                }
                sessionRouter.forwardTextToWorker(ctx.channel(), text);
                break;
            case "socks5_tunnel_data":
                // SOCKS5 隧道数据，转发到 worker
                int socks5BodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (socks5BodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toWorker");
                }
                sessionRouter.forwardTextToWorker(ctx.channel(), text);
                break;
            default:
                // 其他消息直接转发
                sessionRouter.forwardTextToWorker(ctx.channel(), text);
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
            log.debug("Access开始接收分片消息: fragId={}, fragCount={}", fragId, fragCount);
        }

        // 存储分片元数据
        fragCtx.fragmentMetas[seqIdx] = text;

        // 设置待处理的 fragId（等待二进制分片）
        pendingBinaryFragId.put(ctx.channel().id().asShortText(), fragId);

        // 如果是第一个分片，设置 binary target
        if (seqIdx == 0) {
            sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toWorker");
        }

        log.debug("Access收到分片元数据: fragId={}, seqIdx={}/{}", fragId, seqIdx, fragCount);
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
            sessionRouter.forwardBinaryToWorker(ctx.channel(), body);
        }
    }

    /**
     * 处理分片二进制数据
     */
    private void handleFragmentedBinary(ChannelHandlerContext ctx, byte[] body, String fragId) throws Exception {
        FragContext fragCtx = fragCache.get(fragId);
        if (fragCtx == null) {
            log.warn("未找到分片上下文: fragId={}", fragId);
            sessionRouter.forwardBinaryToWorker(ctx.channel(), body);
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

        log.debug("Access收到分片数据: fragId={}, placedIdx={}/{}", fragId, placedIdx, fragCtx.fragCount);

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

        log.debug("Access组装分片消息完成: fragId={}, totalSize={}", fragCtx.fragId, totalSize);

        // 使用第一个分片的元数据，移除分片标记后转发
        Map<String, Object> meta = objectMapper.readValue(fragCtx.metadata, Map.class);
        meta.remove("fragId");
        meta.remove("seqIdx");
        meta.remove("fragCount");

        String forwardedMeta = objectMapper.writeValueAsString(meta);
        sessionRouter.forwardTextToWorker(ctx.channel(), forwardedMeta);
        sessionRouter.forwardBinaryToWorker(ctx.channel(), assembledData);
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
                    log.debug("发送心跳到Access");
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
                log.warn("Access清理过期分片: fragId={}, age={}ms", entry.getKey(), now - entry.getValue().timestamp);
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
        log.error("Access WebSocket处理器异常", cause);
        ctx.close();
    }
}
