package com.unilink.proxy.server;

import com.unilink.proxy.config.ProxyConfig;
import com.unilink.proxy.handler.ProxyRequestHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class HttpProxyChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpProxyChannelHandler.class);

    private final ProxyRequestHandler requestHandler;
    private final ProxyConfig config;
    private Channel outboundChannel;

    public HttpProxyChannelHandler(ProxyRequestHandler requestHandler, ProxyConfig config) {
        this.requestHandler = requestHandler;
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Basic Auth 认证
        if (!authenticate(ctx, request)) {
            return;
        }

        String method = request.method().name();

        // 所有请求都通过 Worker 代理，包括 HTTPS 的 CONNECT
        requestHandler.handleHttpRequest(ctx, request);
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
            ProxyConfig.BasicAuth basicAuth = config.getHttp().getBasicAuth();

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

    private void handleConnect(ChannelHandlerContext ctx, FullHttpRequest request) {
        String host = request.uri();
        String[] parts = host.split(":");
        String targetHost = parts[0];
        int targetPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;

        log.info("收到CONNECT请求: {}:{}", targetHost, targetPort);

        // 直接建立与目标服务器的连接
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new HttpResponseDecoder());
                        ch.pipeline().addLast(new HttpRequestEncoder());
                        ch.pipeline().addLast(new TunnelResponseHandler(ctx.channel()));
                    }
                });

        bootstrap.connect(targetHost, targetPort).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                // 连接成功，返回200 Connection Established
                log.info("已建立到目标服务器的连接: {}:{}", targetHost, targetPort);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK
                );
                ctx.writeAndFlush(response).addListener(f -> {
                    // 隧道建立成功，后续数据直接转发
                    ctx.pipeline().remove(HttpProxyChannelHandler.this);
                    ctx.pipeline().addLast(new TunnelForwardHandler(future.channel()));
                    outboundChannel = future.channel();
                    future.channel().pipeline().addLast(new TunnelForwardHandler(ctx.channel()));
                });
            } else {
                log.error("连接目标服务器失败: {}:{}", targetHost, targetPort);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.BAD_GATEWAY
                );
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            outboundChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP代理处理器异常", cause);
        ctx.close();
    }

    // 处理CONNECT响应（直接返回给客户端）
    private static class TunnelResponseHandler extends SimpleChannelInboundHandler<HttpResponse> {
        private final Channel clientChannel;

        TunnelResponseHandler(Channel clientChannel) {
            this.clientChannel = clientChannel;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpResponse response) {
            // CONNECT响应直接转发给客户端
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }
    }

    // 隧道数据转发
    private static class TunnelForwardHandler extends ChannelInboundHandlerAdapter {
        private final Channel oppositeChannel;

        TunnelForwardHandler(Channel oppositeChannel) {
            this.oppositeChannel = oppositeChannel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            oppositeChannel.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            oppositeChannel.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}