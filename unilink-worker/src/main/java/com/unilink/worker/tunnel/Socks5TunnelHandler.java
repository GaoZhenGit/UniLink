package com.unilink.worker.tunnel;

import com.unilink.worker.client.WorkerWebSocketClient;
import com.unilink.worker.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SOCKS5 隧道处理器
 * 处理 SOCKS5 CONNECT 请求，建立到目标服务器的连接
 */
@Component
public class Socks5TunnelHandler {

    private static final Logger log = LoggerFactory.getLogger(Socks5TunnelHandler.class);

    // SOCKS5 回复状态码
    private static final byte REP_SUCCESS = 0x00;
    private static final byte REP_GENERAL_FAILURE = 0x01;
    private static final byte REP_CONNECTION_NOT_ALLOWED = 0x02;
    private static final byte REP_NETWORK_UNREACHABLE = 0x03;
    private static final byte REP_HOST_UNREACHABLE = 0x04;
    private static final byte REP_CONNECTION_REFUSED = 0x05;
    private static final byte REP_TTL_EXPIRED = 0x06;
    private static final byte REP_COMMAND_NOT_SUPPORTED = 0x07;
    private static final byte REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    @Autowired
    private WorkerWebSocketClient wsClient;

    @Autowired
    private WorkerConfig workerConfig;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Socks5TunnelContext> activeTunnels = new ConcurrentHashMap<>();

    /**
     * 处理 SOCKS5 CONNECT 请求
     */
    public void handleSocks5Connect(String msgId, String username, String host, int port) {
        log.info("收到SOCKS5 CONNECT请求: {}:{} (msgId={})", host, port, msgId);

        executor.submit(() -> {
            SocketChannel targetChannel = null;
            try {
                // 建立到目标服务器的连接（带超时）
                targetChannel = SocketChannel.open();
                int connectTimeout = workerConfig.getHttp().getConnectTimeout();
                targetChannel.socket().connect(new InetSocketAddress(host, port), connectTimeout);

                // 发送 SOCKS5 成功响应给 Proxy
                sendSocks5Response(msgId, (byte) 0x00); // SUCCESS

                // 保存隧道上下文并启动转发
                Socks5TunnelContext ctx = new Socks5TunnelContext(msgId, targetChannel);
                activeTunnels.put(msgId, ctx);
                startForwarding(ctx);
            } catch (SocketTimeoutException e) {
                log.warn("SOCKS5 连接超时: {}:{} (超时={}ms)", host, port, workerConfig.getHttp().getConnectTimeout());
                sendSocks5Response(msgId, REP_TTL_EXPIRED);
                closeQuietly(targetChannel);
            } catch (Exception e) {
                log.error("SOCKS5 隧道建立失败: {}:{}", host, port, e);
                byte status = determineFailureStatus(e);
                sendSocks5Response(msgId, status);
                closeQuietly(targetChannel);
            }
        });
    }

    /**
     * 确定失败状态码
     */
    private byte determineFailureStatus(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String className = e.getClass().getName();

        // UnknownHostException / 无法解析域名
        if (className.contains("UnknownHost") || msg.contains("unknown host") || msg.contains("no such host")) {
            return REP_GENERAL_FAILURE;
        }
        // 连接被拒绝
        if (msg.contains("refused") || msg.contains("connection refused")) {
            return REP_CONNECTION_REFUSED;
        }
        // 网络不可达
        if (msg.contains("unreachable") || className.contains("NoRouteToHost")) {
            return REP_NETWORK_UNREACHABLE;
        }
        // 超时
        if (msg.contains("timeout") || className.contains("SocketTimeoutException")) {
            return REP_TTL_EXPIRED;
        }
        return REP_GENERAL_FAILURE;
    }

    /**
     * 安全关闭 channel
     */
    private void closeQuietly(SocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 发送 SOCKS5 响应
     * VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
     */
    private void sendSocks5Response(String msgId, byte status) {
        try {
            // SOCKS5 响应
            byte[] response = new byte[10];
            response[0] = 0x05; // VER
            response[1] = status; // REP
            response[2] = 0x00; // RSV
            response[3] = 0x01; // ATYP = IPv4
            // BND.ADDR = 0.0.0.0 (bytes 4-7)
            // BND.PORT = 0 (bytes 8-9, big-endian)

            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("msgId", msgId);
            msg.put("type", "socks5_response");
            msg.put("status", status & 0xFF);
            msg.put("bodyLen", response.length);

            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg);
            wsClient.sendMessage(json);
            wsClient.sendBinaryMessage(response);

            log.debug("SOCKS5 响应已发送: msgId={}, status={}", msgId, status);
        } catch (Exception e) {
            log.error("发送 SOCKS5 响应失败", e);
        }
    }

    /**
     * 处理来自 Proxy 的隧道数据
     */
    public void handleTunnelData(String msgId, byte[] data) {
        Socks5TunnelContext ctx = activeTunnels.get(msgId);
        if (ctx != null && ctx.targetChannel.isOpen()) {
            try {
                ctx.targetChannel.write(ByteBuffer.wrap(data));
                log.debug("SOCKS5 转发数据到目标: msgId={}, len={}", msgId, data.length);
            } catch (IOException e) {
                log.error("转发数据到目标失败", e);
                closeTunnel(msgId);
            }
        }
    }

    /**
     * 启动双向数据转发
     */
    private void startForwarding(Socks5TunnelContext ctx) {
        // 从目标服务器读取数据，转发给 Proxy
        executor.submit(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            try {
                while (ctx.targetChannel.isOpen()) {
                    buffer.clear();
                    int read = ctx.targetChannel.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    if (read > 0) {
                        buffer.flip();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);
                        sendTunnelData(ctx.msgId, data);
                    }
                }
            } catch (IOException e) {
                log.debug("从目标读取数据结束: {}", e.getMessage());
            } finally {
                closeTunnel(ctx.msgId);
            }
        });
    }

    /**
     * 发送隧道数据
     */
    private void sendTunnelData(String msgId, byte[] data) {
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("msgId", msgId);
            msg.put("type", "socks5_tunnel_data");
            msg.put("bodyLen", data.length);

            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg);
            wsClient.sendMessage(json);
            wsClient.sendBinaryMessage(data);
            log.debug("SOCKS5 发送隧道数据: msgId={}, len={}", msgId, data.length);
        } catch (Exception e) {
            log.error("发送隧道数据失败", e);
        }
    }

    /**
     * 关闭隧道
     */
    public void closeTunnel(String msgId) {
        Socks5TunnelContext ctx = activeTunnels.remove(msgId);
        if (ctx != null) {
            try {
                ctx.targetChannel.close();
            } catch (IOException ignored) {}
            log.debug("SOCKS5 隧道已关闭: {}", msgId);
        }
    }

    private static class Socks5TunnelContext {
        final String msgId;
        final SocketChannel targetChannel;

        Socks5TunnelContext(String msgId, SocketChannel targetChannel) {
            this.msgId = msgId;
            this.targetChannel = targetChannel;
        }
    }
}
