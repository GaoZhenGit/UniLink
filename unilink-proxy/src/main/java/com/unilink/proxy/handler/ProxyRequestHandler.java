package com.unilink.proxy.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.proxy.config.ProxyConfig;
import com.unilink.proxy.server.WorkerConnectionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProxyRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyRequestHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WorkerConnectionManager connectionManager;

    @Autowired
    private ProxyConfig config;

    // 保存最后一次发送的 msgId（用于 CONNECT 回调）
    private String lastMsgId;
    private final Map<String, Runnable> connectCallbacks = new ConcurrentHashMap<>();

    public void handleHttpRequest(ChannelHandlerContext clientCtx, FullHttpRequest request) {
        String msgId = UUID.randomUUID().toString();
        String method = request.method().name();
        String url = request.uri();
        int bodyLen = request.content().readableBytes();

        // 保存最后一次 msgId
        this.lastMsgId = msgId;

        // 构建HTTP请求消息
        Map<String, Object> msg = new HashMap<>();
        msg.put("msgId", msgId);
        msg.put("type", "http_request");
        msg.put("method", method);
        msg.put("url", url);
        msg.put("bodyLen", bodyLen);

        // 转换headers
        Map<String, String> headers = new HashMap<>();
        request.headers().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        msg.put("headers", headers);

        // 存储客户端上下文，用于返回响应
        connectionManager.registerPendingRequest(msgId, clientCtx, method);

        try {
            // 发送给Worker（JSON头 + 二进制body）
            String jsonHeader = objectMapper.writeValueAsString(msg);
            byte[] body = new byte[bodyLen];
            if (bodyLen > 0) {
                request.content().getBytes(0, body);
            }

            Channel workerChannel = connectionManager.getAvailableWorker();
            if (workerChannel == null) {
                log.warn("没有可用的Worker连接");
                sendErrorResponse(clientCtx, HttpResponseStatus.BAD_GATEWAY, "No worker available");
                connectionManager.removePendingRequest(msgId);
                return;
            }

            // 发送JSON头（文本帧）
            workerChannel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(jsonHeader));

            // 发送二进制body（二进制帧）
            if (bodyLen > 0) {
                workerChannel.writeAndFlush(new io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame(
                        request.content().retainedSlice()
                ));
            }

            log.info("HTTP请求已转发给Worker: {} {} (msgId={})", method, url, msgId);
        } catch (Exception e) {
            log.error("转发HTTP请求失败", e);
            sendErrorResponse(clientCtx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            connectionManager.removePendingRequest(msgId);
        }
    }

    public String getLastMsgId(ChannelHandlerContext ctx) {
        return lastMsgId;
    }

    public void registerConnectCallback(String msgId, Runnable callback) {
        connectCallbacks.put(msgId, callback);
        log.info("已注册CONNECT回调: {}", msgId);
    }

    public void sendResponse(String msgId, int statusCode, Map<String, String> headers, byte[] body, boolean finished) {
        ChannelHandlerContext clientCtx = connectionManager.getPendingRequest(msgId);
        if (clientCtx == null) {
            log.warn("未找到对应的客户端上下文: {}", msgId);
            return;
        }

        String method = connectionManager.getPendingRequestMethod(msgId);
        boolean isConnect = "CONNECT".equalsIgnoreCase(method);

        try {
            // CONNECT 请求特殊处理
            if (isConnect && statusCode == 200) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK
                );
                clientCtx.writeAndFlush(response);
                log.info("CONNECT隧道响应已发送到客户端: msgId={}", msgId);

                // 调用回调切换到隧道模式
                Runnable callback = connectCallbacks.remove(msgId);
                if (callback != null) {
                    callback.run();
                }
                return;
            }

            // 普通 HTTP 响应
            if (body != null && body.length > 0) {
                ByteBuf content = clientCtx.channel().alloc().buffer(body.length);
                content.writeBytes(body);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(statusCode),
                        content
                );

                if (headers != null) {
                    headers.forEach(response.headers()::set);
                }

                if (finished) {
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                } else {
                    response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                }

                clientCtx.writeAndFlush(response);
            } else if (finished) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(statusCode)
                );
                if (headers != null) {
                    headers.forEach(response.headers()::set);
                }
                clientCtx.writeAndFlush(response);
            }

            if (finished) {
                clientCtx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                // CONNECT 隧道不删除 pending request，等待后续 tunnel_data
                if (!isConnect) {
                    connectionManager.removePendingRequest(msgId);
                }
                log.info("HTTP响应完成: msgId={}", msgId);
            }
        } catch (Exception e) {
            log.error("发送HTTP响应失败: {}", msgId, e);
            clientCtx.close();
        }
    }

    /**
     * 发送隧道数据到 Worker（客户端 -> 目标服务器）
     */
    public void sendTunnelData(String msgId, byte[] data) {
        // 获取 Worker 通道
        Channel workerChannel = connectionManager.getAvailableWorker();
        if (workerChannel == null) {
            log.warn("没有可用的Worker连接: {}", msgId);
            return;
        }

        try {
            if (data != null && data.length > 0) {
                log.info("发送隧道数据到Worker: {} bytes, msgId={}", data.length, msgId);

                // 构建 tunnel_data 消息
                Map<String, Object> msg = new HashMap<>();
                msg.put("msgId", msgId);
                msg.put("type", "tunnel_data");
                msg.put("bodyLen", data.length);

                String json = objectMapper.writeValueAsString(msg);

                // 发送 JSON 头（文本帧）
                workerChannel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(json));

                // 发送二进制数据（二进制帧）
                workerChannel.writeAndFlush(new io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame(
                        io.netty.buffer.Unpooled.wrappedBuffer(data)
                ));
            }
        } catch (Exception e) {
            log.error("发送隧道数据到Worker失败: {}", msgId, e);
        }
    }

    /**
     * 从 Worker 收到隧道数据，转发给客户端（目标服务器 -> 客户端）
     */
    public void sendTunnelDataToClient(String msgId, byte[] data) {
        ChannelHandlerContext clientCtx = connectionManager.getPendingRequest(msgId);
        if (clientCtx == null) {
            log.warn("未找到对应的客户端上下文: {}", msgId);
            return;
        }

        try {
            if (data != null && data.length > 0) {
                log.info("发送隧道数据到客户端 {} bytes, channel.isActive={}, msgId={}",
                        data.length, clientCtx.channel().isActive(), msgId);
                ByteBuf content = clientCtx.channel().alloc().buffer(data.length);
                content.writeBytes(data);
                clientCtx.writeAndFlush(content);
            }
        } catch (Exception e) {
            log.error("发送隧道数据到客户端失败: {}", msgId, e);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status
        );
        response.content().writeBytes(message.getBytes(StandardCharsets.UTF_8));
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}