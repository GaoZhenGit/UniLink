package com.unilink.access.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.access.config.AccessConfig;
import com.unilink.access.websocket.AccessWebSocketClient;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
public class HttpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PendingRequestManager pendingRequestManager;

    @Autowired
    private AccessConfig config;

    @Autowired
    private AccessWebSocketClient wsClient;

    private String lastMsgId;
    private final Map<String, Runnable> connectCallbacks = new ConcurrentHashMap<>();

    public void handleHttpRequest(ChannelHandlerContext clientCtx, FullHttpRequest request) {
        String msgId = UUID.randomUUID().toString();
        String method = request.method().name();
        String url = request.uri();
        int bodyLen = request.content().readableBytes();

        this.lastMsgId = msgId;

        Map<String, Object> msg = new HashMap<>();
        msg.put("msgId", msgId);
        msg.put("type", "http_request");
        msg.put("method", method);
        msg.put("url", url);
        msg.put("bodyLen", bodyLen);

        Map<String, String> headers = new HashMap<>();
        request.headers().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        msg.put("headers", headers);

        pendingRequestManager.registerPendingRequest(msgId, clientCtx, method);

        try {
            String jsonHeader = objectMapper.writeValueAsString(msg);
            byte[] body = new byte[bodyLen];
            if (bodyLen > 0) {
                request.content().getBytes(0, body);
            }

            if (!wsClient.isConnected()) {
                log.error("WebSocket未连接到Proxy");
                sendErrorResponse(clientCtx, HttpResponseStatus.BAD_GATEWAY, "Not connected to proxy");
                pendingRequestManager.removePendingRequest(msgId);
                return;
            }

            wsClient.sendMessage(jsonHeader, bodyLen > 0 ? body : null);
            log.info("HTTP请求已转发给Proxy: {} {} (msgId={})", method, url, msgId);
        } catch (Exception e) {
            log.error("转发HTTP请求失败: {} {}", msgId, e.getMessage());
            sendErrorResponse(clientCtx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            pendingRequestManager.removePendingRequest(msgId);
        }
    }

    public String getLastMsgId(ChannelHandlerContext ctx) {
        return lastMsgId;
    }

    public void registerConnectCallback(String msgId, Runnable callback) {
        connectCallbacks.put(msgId, callback);
        log.debug("已注册CONNECT回调: {}", msgId);
    }

    public void sendResponse(String msgId, int statusCode, Map<String, String> headers, byte[] body, boolean finished) {
        ChannelHandlerContext clientCtx = pendingRequestManager.getPendingRequest(msgId);
        if (clientCtx == null) {
            log.warn("未找到对应的客户端上下文: {}", msgId);
            return;
        }

        String method = pendingRequestManager.getPendingRequestMethod(msgId);
        boolean isConnect = "CONNECT".equalsIgnoreCase(method);

        try {
            if (isConnect && statusCode == 200) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK
                );
                clientCtx.writeAndFlush(response);
                log.debug("CONNECT隧道响应已发送到客户端: msgId={}", msgId);

                Runnable callback = connectCallbacks.remove(msgId);
                if (callback != null) {
                    callback.run();
                }
                return;
            }

            boolean headersSent = pendingRequestManager.isHeadersSent(msgId);

            if (!headersSent) {
                HttpResponse response = new DefaultHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(statusCode)
                );

                if (headers != null) {
                    headers.forEach(response.headers()::set);
                }
                response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

                clientCtx.write(response);
                pendingRequestManager.setHeadersSent(msgId, statusCode, headers);
                log.debug("发送响应头: msgId={}, statusCode={}", msgId, statusCode);
            }

            if (body != null && body.length > 0) {
                ByteBuf content = clientCtx.channel().alloc().buffer(body.length);
                content.writeBytes(body);
                HttpContent httpContent = new DefaultHttpContent(content);
                clientCtx.write(httpContent);
                log.debug("发送数据块: msgId={}, len={}", msgId, body.length);
            }

            if (finished) {
                clientCtx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                if (!isConnect) {
                    pendingRequestManager.removePendingRequest(msgId);
                }
                log.debug("HTTP响应完成: msgId={}", msgId);
            } else {
                clientCtx.flush();
            }
        } catch (Exception e) {
            log.error("发送HTTP响应失败: {}", msgId, e);
            clientCtx.close();
        }
    }

    public void sendTunnelData(String msgId, byte[] data) {
        try {
            if (data != null && data.length > 0) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("msgId", msgId);
                msg.put("type", "tunnel_data");
                msg.put("bodyLen", data.length);

                String json = objectMapper.writeValueAsString(msg);
                wsClient.sendMessage(json, data);
            }
        } catch (Exception e) {
            log.error("发送隧道数据到Proxy失败: {}", msgId, e);
        }
    }

    public void sendTunnelDataToClient(String msgId, byte[] data) {
        ChannelHandlerContext clientCtx = pendingRequestManager.getPendingRequest(msgId);
        if (clientCtx == null) {
            log.warn("未找到对应的客户端上下文: {}", msgId);
            return;
        }

        try {
            if (data != null && data.length > 0) {
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
