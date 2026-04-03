package com.unilink.proxy.websocket;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public abstract class TextWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(TextWebSocketFrameHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, (TextWebSocketFrame) frame);
        } else if (frame instanceof BinaryWebSocketFrame) {
            handleBinaryFrame(ctx, (BinaryWebSocketFrame) frame);
        } else if (frame instanceof PongWebSocketFrame) {
            log.debug("收到Pong");
        } else if (frame instanceof PingWebSocketFrame) {
            // 自动回复pong
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else {
            throw new UnsupportedOperationException("不支持的WebSocket帧类型: " + frame.getClass().getName());
        }
    }

    protected void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        throw new UnsupportedOperationException("子类必须实现handleTextFrame方法");
    }

    protected void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        // 默认不处理二进制帧
    }
}
