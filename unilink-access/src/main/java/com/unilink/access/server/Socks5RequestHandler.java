package com.unilink.access.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.access.websocket.AccessWebSocketClient;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SOCKS5 请求处理器
 * 管理 SOCKS5 隧道上下文，通过 WebSocket 与 Proxy 通信
 */
@Component
public class Socks5RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(Socks5RequestHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AccessWebSocketClient wsClient;

    // msgId -> Socks5TunnelContext
    private final Map<String, Socks5TunnelContext> tunnels = new ConcurrentHashMap<>();

    /**
     * 处理 SOCKS5 连接请求
     */
    public String handleSocks5Connect(
            ChannelHandlerContext clientCtx,
            String username,
            String host,
            int port,
            Runnable onSuccess,
            Consumer<String> onFailure) {

        String msgId = java.util.UUID.randomUUID().toString();

        Socks5TunnelContext ctx = new Socks5TunnelContext(
                msgId, clientCtx, username, host, port, onSuccess, onFailure
        );
        tunnels.put(msgId, ctx);

        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("msgId", msgId);
            msg.put("type", "socks5_connect");
            msg.put("username", username != null ? username : "");
            msg.put("host", host);
            msg.put("port", port);

            String json = objectMapper.writeValueAsString(msg);
            wsClient.sendMessage(json, null);
            log.info("SOCKS5 连接请求已发送: {}:{} (msgId={})", host, port, msgId);
        } catch (Exception e) {
            log.error("发送 SOCKS5 连接请求失败", e);
            tunnels.remove(msgId);
            onFailure.accept("Failed to send request");
        }

        return msgId;
    }

    /**
     * 处理来自 Proxy 的 SOCKS5 响应
     */
    public void handleSocks5Response(String msgId, int statusCode, String message) {
        Socks5TunnelContext ctx = tunnels.get(msgId);
        if (ctx == null) {
            log.warn("未找到 SOCKS5 隧道上下文: {}", msgId);
            return;
        }

        if (statusCode == 0) {
            ctx.onSuccess.run();
        } else {
            ctx.onFailure.accept(message != null ? message : "Connection failed");
            tunnels.remove(msgId);
        }
    }

    /**
     * 接收来自 Proxy 的隧道数据
     */
    public void handleTunnelData(String msgId, byte[] data) {
        Socks5TunnelContext ctx = tunnels.get(msgId);
        if (ctx != null && ctx.clientCtx.channel().isActive()) {
            try {
                ByteBuf buf = ctx.clientCtx.channel().alloc().buffer(data.length);
                buf.writeBytes(data);
                ctx.clientCtx.writeAndFlush(buf);
                log.debug("SOCKS5 发送隧道数据到客户端: msgId={}, len={}", msgId, data.length);
            } catch (Exception e) {
                log.error("发送 SOCKS5 隧道数据到客户端失败", e);
                closeTunnel(msgId);
            }
        }
    }

    /**
     * 发送隧道数据到 Proxy
     */
    public void sendTunnelData(String msgId, byte[] data) {
        Socks5TunnelContext ctx = tunnels.get(msgId);
        if (ctx == null) {
            return;
        }

        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("msgId", msgId);
            msg.put("type", "socks5_tunnel_data");
            msg.put("bodyLen", data.length);

            wsClient.sendMessageWithBody(objectMapper.writeValueAsString(msg), data);
            log.debug("SOCKS5 发送隧道数据到 Proxy: msgId={}, len={}", msgId, data.length);
        } catch (Exception e) {
            log.error("发送 SOCKS5 隧道数据失败", e);
            closeTunnel(msgId);
        }
    }

    /**
     * 关闭隧道
     */
    public void closeTunnel(String msgId) {
        Socks5TunnelContext ctx = tunnels.remove(msgId);
        if (ctx != null) {
            try {
                ctx.clientCtx.close();
            } catch (Exception e) {
                log.debug("关闭 SOCKS5 客户端连接时出错: {}", e.getMessage());
            }
            log.debug("SOCKS5 隧道已关闭: {}", msgId);
        }
    }

    /**
     * 移除隧道上下文（不关闭连接）
     */
    public void removeTunnel(String msgId) {
        tunnels.remove(msgId);
    }

    /**
     * SOCKS5 隧道上下文
     */
    private static class Socks5TunnelContext {
        final String msgId;
        final ChannelHandlerContext clientCtx;
        final String username;
        final String host;
        final int port;
        final Runnable onSuccess;
        final Consumer<String> onFailure;

        Socks5TunnelContext(String msgId,
                             ChannelHandlerContext clientCtx,
                             String username, String host, int port,
                             Runnable onSuccess, Consumer<String> onFailure) {
            this.msgId = msgId;
            this.clientCtx = clientCtx;
            this.username = username;
            this.host = host;
            this.port = port;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }
    }
}
