package com.unilink.access.server;

import com.unilink.access.config.AccessConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * SOCKS5 协议处理器
 * 实现完整的状态机：握手 -> 认证 -> 连接请求 -> 数据传输
 */
public class Socks5ChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Socks5ChannelHandler.class);

    // SOCKS5 状态枚举
    private enum Socks5State {
        GREETING,          // 握手阶段
        AUTHENTICATION,    // 认证阶段
        CONNECT_REQUEST,   // 连接请求
        FORWARDING         // 数据转发
    }

    // SOCKS5 地址类型
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;

    // SOCKS5 命令
    private static final byte CMD_CONNECT = 0x01;
    private static final byte CMD_BIND = 0x02;
    private static final byte CMD_UDP = 0x03;

    // SOCKS5 回复状态
    private static final byte REP_SUCCESS = 0x00;
    private static final byte REP_GENERAL_FAILURE = 0x01;
    private static final byte REP_CONNECTION_NOT_ALLOWED = 0x02;
    private static final byte REP_NETWORK_UNREACHABLE = 0x03;
    private static final byte REP_HOST_UNREACHABLE = 0x04;
    private static final byte REP_CONNECTION_REFUSED = 0x05;
    private static final byte REP_TTL_EXPIRED = 0x06;
    private static final byte REP_COMMAND_NOT_SUPPORTED = 0x07;
    private static final byte REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    // 认证方法
    private static final byte AUTH_NO_AUTH = 0x00;
    private static final byte AUTH_GSSAPI = 0x01;
    private static final byte AUTH_PASSWORD = 0x02;
    private static final byte AUTH_NO_ACCEPTABLE = (byte) 0xFF;

    private final AccessConfig accessConfig;
    private final Socks5RequestHandler requestHandler;

    public Socks5ChannelHandler(AccessConfig accessConfig, Socks5RequestHandler requestHandler) {
        this.accessConfig = accessConfig;
        this.requestHandler = requestHandler;
    }

    private Socks5State state = Socks5State.GREETING;
    private String remoteHost;
    private int remotePort;
    private String msgId;

    // 保存认证信息
    private String username;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;

            switch (state) {
                case GREETING:
                    handleGreeting(ctx, buf);
                    break;
                case AUTHENTICATION:
                    handleAuthentication(ctx, buf);
                    break;
                case CONNECT_REQUEST:
                    handleConnectRequest(ctx, buf);
                    break;
                case FORWARDING:
                    // 数据转发阶段不处理
                    break;
            }
        }
    }

    /**
     * 处理客户端 Greeting
     * VER | NMETHODS | METHODS
     */
    private void handleGreeting(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (buf.readableBytes() < 2) {
            return; // 数据不完整，等待更多数据
        }

        byte ver = buf.readByte();
        if (ver != 0x05) {
            log.warn("不支持的 SOCKS 版本: {}", ver);
            ctx.close();
            return;
        }

        byte nmethods = buf.readByte();
        if (buf.readableBytes() < nmethods) {
            return; // 等待更多数据
        }

        byte[] methods = new byte[nmethods];
        buf.readBytes(methods);

        // 选择认证方法
        byte selectedMethod = selectAuthMethod(methods);

        // 发送方法选择响应
        ByteBuf response = ctx.alloc().buffer(2);
        response.writeByte(0x05); // VER
        response.writeByte(selectedMethod);
        ctx.writeAndFlush(response);

        if (selectedMethod == AUTH_NO_AUTH) {
            log.info("SOCKS5 无认证模式");
            state = Socks5State.CONNECT_REQUEST;
            if (buf.readableBytes() > 0) {
                channelRead(ctx, buf);
            }
        } else if (selectedMethod == AUTH_PASSWORD) {
            log.info("SOCKS5 用户名密码认证模式");
            state = Socks5State.AUTHENTICATION;
            if (buf.readableBytes() > 0) {
                channelRead(ctx, buf);
            }
        } else if (selectedMethod == AUTH_NO_ACCEPTABLE) {
            log.warn("SOCKS5 不支持任何认证方法");
            ctx.close();
        }
    }

    /**
     * 选择认证方法
     */
    private byte selectAuthMethod(byte[] clientMethods) {
        if (accessConfig.getSocks5().getAuth().isEnabled()) {
            // 鉴权开启时，只接受用户名密码认证
            for (byte m : clientMethods) {
                if (m == AUTH_PASSWORD) {
                    return AUTH_PASSWORD;
                }
            }
            // curl 不提供 AUTH_PASSWORD，拒绝
            return AUTH_NO_ACCEPTABLE;
        }

        // 鉴权关闭时，优先无认证
        for (byte m : clientMethods) {
            if (m == AUTH_NO_AUTH) {
                return AUTH_NO_AUTH;
            }
        }
        // 其次用户名密码
        for (byte m : clientMethods) {
            if (m == AUTH_PASSWORD) {
                return AUTH_PASSWORD;
            }
        }

        return AUTH_NO_ACCEPTABLE;
    }

    /**
     * 处理用户名密码认证
     * VER | ULEN | USERNAME | PLEN | PASSWORD
     */
    private void handleAuthentication(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (buf.readableBytes() < 2) {
            return;
        }

        byte ver = buf.readByte();
        if (ver != 0x01) {
            log.warn("不支持的认证协议版本: {}", ver);
            sendAuthResponse(ctx, (byte) 0xFF);
            return;
        }

        byte ulen = buf.readByte();
        if (buf.readableBytes() < ulen + 1) {
            return;
        }

        byte[] usernameBytes = new byte[ulen];
        buf.readBytes(usernameBytes);
        String username = new String(usernameBytes, StandardCharsets.UTF_8);

        byte plen = buf.readByte();
        if (buf.readableBytes() < plen) {
            return;
        }

        byte[] passwordBytes = new byte[plen];
        buf.readBytes(passwordBytes);
        String password = new String(passwordBytes, StandardCharsets.UTF_8);

        // 验证凭据
        boolean authSuccess = authenticate(username, password);
        sendAuthResponse(ctx, authSuccess ? (byte) 0x00 : (byte) 0xFF);

        if (authSuccess) {
            this.username = username;
            log.debug("SOCKS5 认证成功: {}", username);
            state = Socks5State.CONNECT_REQUEST;
            if (buf.readableBytes() > 0) {
                channelRead(ctx, buf);
            }
        } else {
            log.warn("SOCKS5 认证失败: {}", username);
            ctx.close();
        }
    }

    /**
     * 发送认证响应
     */
    private void sendAuthResponse(ChannelHandlerContext ctx, byte status) {
        ByteBuf response = ctx.alloc().buffer(2);
        response.writeByte(0x01); // 认证协议版本
        response.writeByte(status);
        ctx.writeAndFlush(response);
    }

    /**
     * 验证用户名密码
     */
    private boolean authenticate(String username, String password) {
        AccessConfig.Auth auth = accessConfig.getSocks5().getAuth();
        return auth.getUsername().equals(username) &&
               auth.getPassword().equals(password);
    }

    /**
     * 处理 CONNECT 请求
     * VER | CMD | RSV | ATYP | DST.ADDR | DST.PORT
     */
    private void handleConnectRequest(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (buf.readableBytes() < 4) {
            return;
        }

        byte ver = buf.readByte();
        if (ver != 0x05) {
            log.warn("SOCKS5 版本不匹配: {}", ver);
            sendCommandResponse(ctx, REP_GENERAL_FAILURE);
            return;
        }

        byte cmd = buf.readByte();
        byte rsv = buf.readByte();
        byte atyp = buf.readByte();

        // 只支持 CONNECT 命令
        if (cmd != CMD_CONNECT) {
            log.warn("SOCKS5 不支持的命令: {} (只支持 CONNECT)", cmd);
            sendCommandResponse(ctx, REP_COMMAND_NOT_SUPPORTED);
            return;
        }

        // 解析目标地址
        try {
            String dstAddr;
            int dstPort;

            switch (atyp) {
                case ATYP_IPV4: // IPv4
                    if (buf.readableBytes() < 6) return;
                    byte[] ipv4 = new byte[4];
                    buf.readBytes(ipv4);
                    dstAddr = String.format("%d.%d.%d.%d",
                            ipv4[0] & 0xFF, ipv4[1] & 0xFF,
                            ipv4[2] & 0xFF, ipv4[3] & 0xFF);
                    dstPort = buf.readUnsignedShort();
                    break;

                case ATYP_DOMAIN: // 域名
                    byte domainLen = buf.readByte();
                    if (buf.readableBytes() < domainLen + 2) return;
                    byte[] domainBytes = new byte[domainLen];
                    buf.readBytes(domainBytes);
                    dstAddr = new String(domainBytes, StandardCharsets.UTF_8);
                    dstPort = buf.readUnsignedShort();
                    break;

                case ATYP_IPV6: // IPv6
                    if (buf.readableBytes() < 18) return;
                    byte[] ipv6 = new byte[16];
                    buf.readBytes(ipv6);
                    dstAddr = "IPv6"; // 简化处理
                    dstPort = buf.readUnsignedShort();
                    break;

                default:
                    log.warn("SOCKS5 不支持的地址类型: {}", atyp);
                    sendCommandResponse(ctx, REP_ADDRESS_TYPE_NOT_SUPPORTED);
                    return;
            }

            this.remoteHost = dstAddr;
            this.remotePort = dstPort;
            log.info("SOCKS5 收到 CONNECT 请求: {}:{}", dstAddr, dstPort);

            // 通过 WebSocket 发送连接请求到 Proxy
            msgId = requestHandler.handleSocks5Connect(
                    ctx, username, dstAddr, dstPort,
                    () -> onTunnelEstablished(ctx),
                    (errorMsg) -> onTunnelFailed(ctx, errorMsg)
            );

        } catch (Exception e) {
            log.error("SOCKS5 解析 CONNECT 请求失败", e);
            sendCommandResponse(ctx, REP_GENERAL_FAILURE);
            ctx.close();
        }
    }

    /**
     * 隧道建立成功回调
     */
    private void onTunnelEstablished(ChannelHandlerContext ctx) {
        // 发送成功响应
        sendCommandResponse(ctx, REP_SUCCESS);

        // 切换到数据转发模式
        state = Socks5State.FORWARDING;

        // 移除自己，添加隧道数据处理器
        ChannelPipeline pipeline = ctx.pipeline();
        if (pipeline.get(Socks5ChannelHandler.class) != null) {
            pipeline.remove(Socks5ChannelHandler.class);
        }
        pipeline.addLast(new Socks5TunnelForwardHandler(requestHandler, msgId));

        log.info("SOCKS5 隧道已建立: {}:{}", remoteHost, remotePort);
    }

    /**
     * 隧道建立失败回调
     */
    private void onTunnelFailed(ChannelHandlerContext ctx, String errorMsg) {
        byte status = REP_HOST_UNREACHABLE;
        if (errorMsg != null) {
            if (errorMsg.contains("refused")) {
                status = REP_CONNECTION_REFUSED;
            } else if (errorMsg.contains("unreachable")) {
                status = REP_NETWORK_UNREACHABLE;
            } else if (errorMsg.contains("timeout")) {
                status = REP_TTL_EXPIRED;
            }
        }

        log.warn("SOCKS5 隧道建立失败: {}:{}, status={}", remoteHost, remotePort, status);
        // 确保响应发送完成后再关闭连接
        ctx.writeAndFlush(sendCommandResponseBuf(ctx, status))
           .addListener(ChannelFutureListener.CLOSE);
    }

    private ByteBuf sendCommandResponseBuf(ChannelHandlerContext ctx, byte status) {
        ByteBuf buf = ctx.alloc().buffer(10);
        buf.writeByte(0x05); // VER
        buf.writeByte(status); // REP
        buf.writeByte(0x00); // RSV
        buf.writeByte(ATYP_IPV4); // ATYP = IPv4
        buf.writeBytes(new byte[]{0, 0, 0, 0}); // BND.ADDR
        buf.writeShort(0); // BND.PORT
        return buf;
    }

    /**
     * 发送 CONNECT 响应
     * VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
     */
    private void sendCommandResponse(ChannelHandlerContext ctx, byte status) {
        ByteBuf buf = ctx.alloc().buffer(10);
        buf.writeByte(0x05); // VER
        buf.writeByte(status); // REP
        buf.writeByte(0x00); // RSV
        buf.writeByte(ATYP_IPV4); // ATYP = IPv4
        // BND.ADDR (4 bytes) - 使用 0.0.0.0
        buf.writeBytes(new byte[]{0, 0, 0, 0});
        // BND.PORT (2 bytes, big-endian) - 使用 0
        buf.writeShort(0);
        ctx.writeAndFlush(buf);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("SOCKS5 客户端断开: {}", ctx.channel().remoteAddress());
        if (msgId != null) {
            requestHandler.closeTunnel(msgId);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("SOCKS5 处理器异常", cause);
        if (msgId != null) {
            requestHandler.closeTunnel(msgId);
        }
        ctx.close();
    }
}
