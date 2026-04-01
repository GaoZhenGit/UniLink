package com.unilink.proxy.config;

import com.unilink.proxy.handler.ProxyRequestHandler;
import com.unilink.proxy.server.WorkerConnectionManager;
import com.unilink.proxy.websocket.ProxyWebSocketHandler;
import com.unilink.proxy.websocket.TextWebSocketFrameHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutionException;

@Configuration
public class WsServerConfig {

    private static final Logger log = LoggerFactory.getLogger(WsServerConfig.class);

    @Autowired
    private ProxyConfig proxyConfig;

    @Autowired
    private WorkerConnectionManager connectionManager;

    @Autowired
    private ProxyRequestHandler requestHandler;

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    @PostConstruct
    public void startWebSocketServer() {
        int port = proxyConfig.getWebsocket().getPort();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            // 简化版：使用HTTP upgrade方式建立WebSocket
            // 生产环境建议配置SSL或使用K8s Ingress处理SSL
            io.netty.bootstrap.ServerBootstrap bootstrap = new io.netty.bootstrap.ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(65536));
                            pipeline.addLast(new WebSocketServerCompressionHandler());
                            pipeline.addLast(new WebSocketServerProtocolHandler("/ws", null, true));
                            // 每次创建新实例
                            ProxyWebSocketHandler handler = new ProxyWebSocketHandler(
                                    connectionManager, requestHandler, proxyConfig);
                            pipeline.addLast(handler);
                        }
                    });

            bootstrap.bind(port).get();
            log.info("WebSocket服务启动成功，端口: {}", port);
        } catch (Exception e) {
            log.error("WebSocket服务启动失败", e);
            shutdown();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("WebSocket服务已关闭");
    }
}