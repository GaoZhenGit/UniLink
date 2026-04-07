package com.unilink.access.server;

import com.unilink.access.config.AccessConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Component
public class Socks5ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(Socks5ProxyServer.class);

    @Autowired
    private AccessConfig accessConfig;

    @Autowired
    private Socks5RequestHandler socks5RequestHandler;

    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @PostConstruct
    public void start() {
        if (!accessConfig.getSocks5().isEnabled()) {
            log.info("SOCKS5 代理服务已禁用");
            return;
        }

        int port = accessConfig.getSocks5().getPort();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            Socks5ChannelHandler handler = new Socks5ChannelHandler(accessConfig, socks5RequestHandler);
                            pipeline.addLast(handler);
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            log.info("SOCKS5 代理服务启动成功，端口: {}", port);
        } catch (Exception e) {
            log.error("SOCKS5 代理服务启动失败", e);
            shutdown();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("SOCKS5 代理服务已关闭");
    }
}
