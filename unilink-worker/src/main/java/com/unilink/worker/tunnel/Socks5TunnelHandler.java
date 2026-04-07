package com.unilink.worker.tunnel;

import com.unilink.worker.client.WorkerWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
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

    @Autowired
    private WorkerWebSocketClient wsClient;

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
                // 建立到目标服务器的连接
                targetChannel = SocketChannel.open();
                targetChannel.configureBlocking(true);
                targetChannel.connect(new InetSocketAddress(host, port));

                if (targetChannel.finishConnect()) {
                    log.info("SOCKS5 隧道建立成功: {}:{}", host, port);

                    // 保存隧道上下文
                    Socks5TunnelContext ctx = new Socks5TunnelContext(msgId, targetChannel);
                    activeTunnels.put(msgId, ctx);

                    // 发送 SOCKS5 成功响应给 Proxy
                    sendSocks5Response(msgId, (byte) 0x00); // SUCCESS

                    // 启动双向转发
                    startForwarding(ctx);
                } else {
                    sendSocks5Response(msgId, (byte) 0x01); // GENERAL_FAILURE
                }
            } catch (Exception e) {
                log.error("SOCKS5 隧道建立失败: {}:{}", host, port, e);
                byte status = determineFailureStatus(e);
                sendSocks5Response(msgId, status);
                if (targetChannel != null) {
                    try {
                        targetChannel.close();
                    } catch (IOException ignored) {}
                }
            }
        });
    }

    /**
     * 确定失败状态码
     */
    private byte determineFailureStatus(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("refused")) {
            return 0x05; // CONNECTION_REFUSED
        } else if (msg.contains("unreachable")) {
            return 0x03; // NETWORK_UNREACHABLE
        } else if (msg.contains("timeout")) {
            return 0x06; // TTL_EXPIRED
        }
        return 0x01; // GENERAL_FAILURE
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
