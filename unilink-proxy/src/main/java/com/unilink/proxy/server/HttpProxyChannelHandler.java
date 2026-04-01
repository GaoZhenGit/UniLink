package com.unilink.proxy.server;

import com.unilink.proxy.config.ProxyConfig;
import com.unilink.proxy.handler.ProxyRequestHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class HttpProxyChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpProxyChannelHandler.class);

    private final ProxyRequestHandler requestHandler;
    private final ProxyConfig config;
    private final WorkerConnectionManager connectionManager;

    // 保存 CONNECT 隧道对应的 Worker Channel
    private static final Map<String, Channel> tunnelChannels = new ConcurrentHashMap<>();

    public HttpProxyChannelHandler(ProxyRequestHandler requestHandler, ProxyConfig config, WorkerConnectionManager connectionManager) {
        this.requestHandler = requestHandler;
        this.config = config;
        this.connectionManager = connectionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Basic Auth 认证
        if (!authenticate(ctx, request)) {
            return;
        }

        String method = request.method().name();

        if ("CONNECT".equalsIgnoreCase(method)) {
            // 处理 CONNECT 请求
            handleConnect(ctx, request);
        } else {
            // 普通 HTTP 请求，转发给 Worker
            requestHandler.handleHttpRequest(ctx, request);
        }
    }

    private void handleConnect(ChannelHandlerContext ctx, FullHttpRequest request) {
        String host = request.uri();
        log.info("收到CONNECT请求: {}", host);

        // 转发 CONNECT 请求给 Worker
        requestHandler.handleHttpRequest(ctx, request);

        // 注册回调，等待 Worker 返回 200 后切换到隧道模式
        String msgId = requestHandler.getLastMsgId(ctx);
        requestHandler.registerConnectCallback(msgId, () -> {
            // Worker 返回 200 后，切换到原始数据转发模式
            switchToTunnelMode(ctx, host, msgId);
        });
    }

    private void switchToTunnelMode(ChannelHandlerContext ctx, String host, String msgId) {
        log.info("CONNECT隧道建立成功，切换到转发模式: {}", host);

        // 使用 execute 确保在正确的 EventLoop 中执行
        ctx.channel().eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = ctx.pipeline();

                // 移除 HTTP 编解码器和当前 handler
                pipeline.remove(HttpServerCodec.class);
                pipeline.remove(HttpObjectAggregator.class);

                // 移除当前 handler（如果还在 pipeline 中）
                if (pipeline.get(HttpProxyChannelHandler.class) != null) {
                    pipeline.remove(HttpProxyChannelHandler.class);
                }

                // 添加原始数据转发 Handler
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP代理处理器异常", cause);
        ctx.close();
    }

    // 隧道数据转发 Handler - 直接转发原始数据
    private static class TunnelDataForwardHandler extends ChannelInboundHandlerAdapter {
        private final ProxyRequestHandler requestHandler;
        private final String msgId;

        public TunnelDataForwardHandler(ProxyRequestHandler requestHandler, String msgId) {
            this.requestHandler = requestHandler;
            this.msgId = msgId;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            // 读取客户端发送的原始数据，转发到 Worker
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                int len = buf.readableBytes();
                byte[] data = new byte[len];
                buf.readBytes(data);

                // 发送到 Worker
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

    // 静态方法供 ProxyRequestHandler 调用
    public static void registerTunnelChannel(String msgId, Channel workerChannel) {
        tunnelChannels.put(msgId, workerChannel);
    }
}