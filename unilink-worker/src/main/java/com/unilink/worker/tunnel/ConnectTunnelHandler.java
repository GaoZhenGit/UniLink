package com.unilink.worker.tunnel;

import com.unilink.worker.client.WorkerWebSocketClient;
import com.unilink.worker.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class ConnectTunnelHandler {

    private static final Logger log = LoggerFactory.getLogger(ConnectTunnelHandler.class);

    @Autowired
    private WorkerWebSocketClient wsClient;

    @Autowired
    private WorkerConfig workerConfig;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, TunnelContext> activeTunnels = new ConcurrentHashMap<>();

    public void handleConnect(String msgId, String host, int port) {
        log.info("收到CONNECT请求: {}:{} (msgId={})", host, port, msgId);

        executor.submit(() -> {
            SocketChannel targetChannel = null;
            int connectTimeout = workerConfig.getHttp().getConnectTimeout();
            try {
                // DNS 解析也需要超时（getByName 本身不受 socket 超时控制）
                InetAddress[] addresses = dnsLookupWithTimeout(host, connectTimeout);

                // 建立到目标服务器的连接
                targetChannel = SocketChannel.open();
                targetChannel.socket().connect(new InetSocketAddress(addresses[0], port), connectTimeout);

                // 保存隧道上下文
                TunnelContext ctx = new TunnelContext(msgId, targetChannel);
                activeTunnels.put(msgId, ctx);

                // 发送200响应给Proxy
                sendConnectResponse(msgId, 200, "Connection Established");

                // 启动双向转发
                startForwarding(ctx);
            } catch (SocketTimeoutException e) {
                log.warn("CONNECT 连接超时: {}:{} (超时={}ms)", host, port, connectTimeout);
                sendConnectResponse(msgId, 504, "Gateway Timeout");
                closeQuietly(targetChannel);
            } catch (Exception e) {
                log.error("CONNECT隧道建立失败: {}:{}", host, port, e);
                sendConnectResponse(msgId, 502, e.getMessage());
                closeQuietly(targetChannel);
            }
        });
    }

    private InetAddress[] dnsLookupWithTimeout(String host, int timeoutMs) throws Exception {
        ExecutorService dnsExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<InetAddress[]> future = dnsExecutor.submit(() -> InetAddress.getAllByName(host));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            dnsExecutor.shutdownNow();
        }
    }

    private void closeQuietly(SocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {}
        }
    }

    private void sendConnectResponse(String msgId, int statusCode, String message) {
        try {
            Map<String, Object> response = new ConcurrentHashMap<>();
            response.put("msgId", msgId);
            response.put("type", "http_response");
            response.put("statusCode", statusCode);
            response.put("bodyLen", message != null ? message.getBytes().length : 0);
            response.put("finished", true);

            byte[] bodyBytes = message != null ? message.getBytes() : null;
            wsClient.sendMessageWithBody(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response),
                    bodyBytes
            );
        } catch (Exception e) {
            log.error("发送CONNECT响应失败", e);
        }
    }

    public void handleTunnelData(String msgId, byte[] data) {
        TunnelContext ctx = activeTunnels.get(msgId);
        if (ctx != null && ctx.targetChannel.isOpen()) {
            try {
                ctx.targetChannel.write(ByteBuffer.wrap(data));
            } catch (IOException e) {
                log.error("转发数据到目标失败", e);
                closeTunnel(msgId);
            }
        }
    }

    private void startForwarding(TunnelContext ctx) {
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
                        // 发送数据给 Proxy
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

    private void sendTunnelData(String msgId, byte[] data) {
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("msgId", msgId);
            msg.put("type", "tunnel_data");
            msg.put("bodyLen", data.length);

            wsClient.sendMessageWithBody(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg),
                    data
            );
        } catch (Exception e) {
            log.error("发送隧道数据失败", e);
        }
    }

    public void closeTunnel(String msgId) {
        TunnelContext ctx = activeTunnels.remove(msgId);
        if (ctx != null) {
            try {
                ctx.targetChannel.close();
            } catch (IOException ignored) {}
            log.debug("CONNECT隧道已关闭: {}", msgId);
        }
    }

    private static class TunnelContext {
        final String msgId;
        final SocketChannel targetChannel;

        TunnelContext(String msgId, SocketChannel targetChannel) {
            this.msgId = msgId;
            this.targetChannel = targetChannel;
        }
    }
}