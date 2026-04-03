package com.unilink.proxy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.proxy.config.ProxyConfig;
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

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Access WebSocket连接建立: {}", ctx.channel().remoteAddress());
        sessionRouter.registerAccess(ctx.channel());
        startHeartbeat(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Access WebSocket连接断开");
        sessionRouter.unregisterAccess(ctx.channel());
        stopHeartbeat(ctx);
    }

    @Override
    protected void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        log.debug("收到Access消息: {}", text.length() > 200 ? text.substring(0, 200) + "..." : text);

        Map<String, Object> msg = objectMapper.readValue(text, Map.class);
        String type = (String) msg.get("type");

        switch (type) {
            case "heartbeat":
                handleHeartbeat(ctx, msg);
                break;
            case "heartbeat_ack":
                log.debug("收到Access心跳响应");
                break;
            case "http_request":
            case "tunnel_data":
                // 标记下一帧二进制数据需要转发
                int bodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (bodyLen > 0) {
                    sessionRouter.setPendingBinaryTarget(ctx.channel().id().asShortText(), "toWorker");
                }
                // 转发到 worker
                sessionRouter.forwardTextToWorker(ctx.channel(), text);
                break;
            default:
                // 其他消息直接转发
                sessionRouter.forwardTextToWorker(ctx.channel(), text);
        }
    }

    @Override
    protected void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        byte[] body = new byte[frame.content().readableBytes()];
        frame.content().readBytes(body);

        // 检查是否有待转发的目标
        String target = sessionRouter.getAndClearPendingBinaryTarget(ctx.channel().id().asShortText());
        if ("toWorker".equals(target)) {
            sessionRouter.forwardBinaryToWorker(ctx.channel(), body);
        } else {
            // 默认转发到 worker
            sessionRouter.forwardBinaryToWorker(ctx.channel(), body);
        }
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
        }, interval, interval, TimeUnit.SECONDS);
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
