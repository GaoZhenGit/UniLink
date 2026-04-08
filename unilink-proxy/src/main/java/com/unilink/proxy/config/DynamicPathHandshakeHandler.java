package com.unilink.proxy.config;

import com.unilink.proxy.websocket.TextWebSocketFrameHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 动态路径 WebSocket 握手处理器
 * 根据 HTTP 请求路径选择对应的处理器并完成握手
 */
public class DynamicPathHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DynamicPathHandshakeHandler.class);

    private final String accessPath;
    private final TextWebSocketFrameHandler accessHandler;
    private final String workerPath;
    private final TextWebSocketFrameHandler workerHandler;

    public DynamicPathHandshakeHandler(String accessPath, TextWebSocketFrameHandler accessHandler,
                                        String workerPath, TextWebSocketFrameHandler workerHandler) {
        this.accessPath = accessPath;
        this.accessHandler = accessHandler;
        this.workerPath = workerPath;
        this.workerHandler = workerHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;

            // 检查是否是 WebSocket 握手请求
            if (isWebSocketRequest(request)) {
                String uri = request.uri();
                // 提取路径（去掉查询参数）
                String path = uri.split("\\?")[0];

                log.info("收到WebSocket握手请求: path={}", path);

                // 选择处理器
                TextWebSocketFrameHandler handler = selectHandler(path);
                if (handler == null) {
                    log.warn("未知的WebSocket路径: {}", path);
                    sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
                    request.release();
                    return;
                }

                // 执行握手
                String webSocketURL = getWebSocketURL(request, path);
                WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                        webSocketURL, null, true, 65536);
                WebSocketServerHandshaker handshaker = factory.newHandshaker(request);

                if (handshaker == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                    request.release();
                    return;
                }

                // 握手
                handshaker.handshake(ctx.channel(), request);

                // 移除自己
                ctx.pipeline().remove(this);

                // 添加业务处理器
                ctx.pipeline().addLast(handler);

                // 触发 channelActive
                handler.channelActive(ctx);

                log.info("WebSocket握手完成: path={}, handler={}", path, handler.getClass().getSimpleName());

                request.release();
            } else {
                // 非 WebSocket 请求，返回错误
                sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
                request.release();
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private boolean isWebSocketRequest(FullHttpRequest request) {
        return request.headers().contains(HttpHeaderNames.UPGRADE) &&
                "websocket".equalsIgnoreCase(request.headers().get(HttpHeaderNames.UPGRADE));
    }

    private TextWebSocketFrameHandler selectHandler(String path) {
        if (path == null) {
            return null;
        }

        if (path.equals(accessPath) || path.startsWith(accessPath)) {
            return accessHandler;
        } else if (path.equals(workerPath) || path.startsWith(workerPath)) {
            return workerHandler;
        }

        return null;
    }

    private String getWebSocketURL(FullHttpRequest request, String path) {
        String host = request.headers().get(HttpHeaderNames.HOST, "localhost");
        return "ws://" + host + path;
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.content().writeBytes(status.toString().getBytes());

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket握手处理异常", cause);
        ctx.close();
    }
}
