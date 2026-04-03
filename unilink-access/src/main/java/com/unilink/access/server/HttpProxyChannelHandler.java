package com.unilink.access.server;

import com.unilink.access.config.AccessConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HttpProxyChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpProxyChannelHandler.class);

    @Autowired
    private AccessConfig config;

    @Autowired
    private HttpRequestHandler requestHandler;

    // 保存 CONNECT 隧道对应的上下文
    private static final Map<String, Channel> tunnelChannels = new ConcurrentHashMap<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Basic Auth 认证
        if (!authenticate(ctx, request)) {
            return;
        }

        String method = request.method().name();

        if ("CONNECT".equalsIgnoreCase(method)) {
            handleConnect(ctx, request);
        } else {
            requestHandler.handleHttpRequest(ctx, request);
        }
    }

    private void handleConnect(ChannelHandlerContext ctx, FullHttpRequest request) {
        String host = request.uri();
        log.info("收到CONNECT请求: {}", host);

        requestHandler.handleHttpRequest(ctx, request);

        String msgId = requestHandler.getLastMsgId(ctx);
        requestHandler.registerConnectCallback(msgId, () -> {
            switchToTunnelMode(ctx, host, msgId);
        });
    }

    private void switchToTunnelMode(ChannelHandlerContext ctx, String host, String msgId) {
        log.info("CONNECT隧道建立成功，切换到转发模式: {}", host);

        ctx.channel().eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = ctx.pipeline();

                pipeline.remove(HttpServerCodec.class);
                pipeline.remove(HttpObjectAggregator.class);

                if (pipeline.get(HttpProxyChannelHandler.class) != null) {
                    pipeline.remove(HttpProxyChannelHandler.class);
                }

                pipeline.addLast(new TunnelDataForwardHandler(requestHandler, msgId));

                log.info("隧道模式已就绪: msgId={}", msgId);
            } catch (Exception e) {
                log.error("切换到隧道模式失败", e);
                ctx.close();
            }
        });
    }

    private boolean authenticate(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!config.getHttp().getBasicAuth().isEnabled()) {
            return true;
        }

        String authHeader = request.headers().get("Proxy-Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            sendUnauthorized(ctx);
            return false;
        }

        try {
            String credentials = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            String[] parts = credentials.split(":", 2);
            if (parts.length != 2) {
                sendUnauthorized(ctx);
                return false;
            }

            String username = parts[0];
            String password = parts[1];
            AccessConfig.BasicAuth basicAuth = config.getHttp().getBasicAuth();

            if (username.equals(basicAuth.getUsername()) && password.equals(basicAuth.getPassword())) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Basic Auth解析失败", e);
        }

        sendUnauthorized(ctx);
        return false;
    }

    private void sendUnauthorized(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED
        );
        response.headers().set("Proxy-Authenticate", "Basic realm=\"Proxy\"");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP代理处理器异常", cause);
        ctx.close();
    }

    // 隧道数据转发 Handler
    private static class TunnelDataForwardHandler extends ChannelInboundHandlerAdapter {
        private final HttpRequestHandler requestHandler;
        private final String msgId;

        public TunnelDataForwardHandler(HttpRequestHandler requestHandler, String msgId) {
            this.requestHandler = requestHandler;
            this.msgId = msgId;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                int len = buf.readableBytes();
                byte[] data = new byte[len];
                buf.readBytes(data);
                requestHandler.sendTunnelData(msgId, data);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("客户端连接断开，关闭隧道: {}", msgId);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("隧道转发异常", cause);
            ctx.close();
        }
    }
}
