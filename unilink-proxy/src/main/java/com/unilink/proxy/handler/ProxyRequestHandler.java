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

@Component
public class ProxyRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyRequestHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WorkerConnectionManager connectionManager;

    @Autowired
    private ProxyConfig config;

    public void handleHttpRequest(ChannelHandlerContext clientCtx, FullHttpRequest request) {
        String msgId = UUID.randomUUID().toString();
        String method = request.method().name();
        String url = request.uri();
        int bodyLen = request.content().readableBytes();

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
        connectionManager.registerPendingRequest(msgId, clientCtx);

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

    public void sendResponse(String msgId, int statusCode, Map<String, String> headers, byte[] body, boolean finished) {
        ChannelHandlerContext clientCtx = connectionManager.getPendingRequest(msgId);
        if (clientCtx == null) {
            log.warn("未找到对应的客户端上下文: {}", msgId);
            return;
        }

        try {
            // 流式响应：只在第一个chunk时发送响应头
            if (body != null && body.length > 0) {
                ByteBuf content = clientCtx.channel().alloc().buffer(body.length);
                content.writeBytes(body);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(statusCode),
                        content
                );

                // 设置headers
                if (headers != null) {
                    headers.forEach(response.headers()::set);
                }

                if (finished) {
                    // 完成的响应：设置 Content-Length
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                } else {
                    // 流式响应：使用 chunked 传输
                    response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                }

                clientCtx.writeAndFlush(response);
            } else if (finished) {
                // 无 body 的响应
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
                connectionManager.removePendingRequest(msgId);
                log.info("HTTP响应完成: msgId={}", msgId);
            }
        } catch (Exception e) {
            log.error("发送HTTP响应失败: {}", msgId, e);
            clientCtx.close();
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