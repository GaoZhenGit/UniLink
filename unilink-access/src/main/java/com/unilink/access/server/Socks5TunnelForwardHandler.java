package com.unilink.access.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOCKS5 隧道数据转发处理器
 * 建立隧道后，客户端和目标服务器之间的所有数据都通过此 Handler 转发
 */
public class Socks5TunnelForwardHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Socks5TunnelForwardHandler.class);

    private final Socks5RequestHandler requestHandler;
    private final String msgId;

    public Socks5TunnelForwardHandler(Socks5RequestHandler requestHandler, String msgId) {
        this.requestHandler = requestHandler;
        this.msgId = msgId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            int len = buf.readableBytes();
            if (len > 0) {
                byte[] data = new byte[len];
                buf.readBytes(data);
                requestHandler.sendTunnelData(msgId, data);
                log.debug("SOCKS5 转发数据到 Proxy: msgId={}, len={}", msgId, len);
            } else {
                buf.release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("SOCKS5 客户端连接断开，关闭隧道: {}", msgId);
        requestHandler.closeTunnel(msgId);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("SOCKS5 隧道转发异常: {}", msgId, cause);
        requestHandler.closeTunnel(msgId);
        ctx.close();
    }
}
