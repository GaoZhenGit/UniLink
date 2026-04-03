package com.unilink.proxy.config;

import com.unilink.proxy.manager.SessionRouter;
import com.unilink.proxy.websocket.AccessHandler;
import com.unilink.proxy.websocket.WorkerHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Configuration
public class WsServerConfig {

    private static final Logger log = LoggerFactory.getLogger(WsServerConfig.class);

    @Autowired
    private ProxyConfig proxyConfig;

    @Autowired
    private SessionRouter sessionRouter;

    @Autowired
    private AccessHandler accessHandler;

    @Autowired
    private WorkerHandler workerHandler;

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    @PostConstruct
    public void startWebSocketServer() {
        int port = proxyConfig.getWebsocket().getPort();
        String accessPath = proxyConfig.getWebsocket().getAccessPath();
        String workerPath = proxyConfig.getWebsocket().getWorkerPath();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
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

                            // 使用自定义握手处理器，支持动态路径
                            pipeline.addLast(new DynamicPathHandshakeHandler(
                                    accessPath, accessHandler,
                                    workerPath, workerHandler
                            ));
                        }
                    });

            bootstrap.bind(port).get();
            log.info("WebSocket服务启动成功，端口: {}, access路径: {}, worker路径: {}",
                    port, accessPath, workerPath);
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
