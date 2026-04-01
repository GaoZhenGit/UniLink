package com.unilink.proxy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.proxy.config.ProxyConfig;
import com.unilink.proxy.handler.ProxyRequestHandler;
import com.unilink.proxy.server.WorkerConnectionManager;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ProxyWebSocketHandler extends TextWebSocketFrameHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WorkerConnectionManager connectionManager;

    @Autowired
    private ProxyRequestHandler requestHandler;

    @Autowired
    private ProxyConfig config;

    // 手动创建实例时的构造函数
    public ProxyWebSocketHandler(WorkerConnectionManager connectionManager,
                                  ProxyRequestHandler requestHandler,
                                  ProxyConfig config) {
        this.connectionManager = connectionManager;
        this.requestHandler = requestHandler;
        this.config = config;
    }

    private Channel workerChannel;
    private final AtomicBoolean heartbeatSent = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatScheduler;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Worker WebSocket连接建立: {}", ctx.channel().remoteAddress());
        workerChannel = ctx.channel();
        connectionManager.registerWorker(ctx.channel());
        startHeartbeat();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Worker WebSocket连接断开");
        connectionManager.removeWorker(ctx.channel());
        stopHeartbeat();
    }

    @Override
    protected void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        log.debug("收到Worker消息: {}", text);

        Map<String, Object> msg = objectMapper.readValue(text, Map.class);
        String type = (String) msg.get("type");

        switch (type) {
            case "http_response":
            case "http_chunk":
                handleHttpResponse(msg);
                break;
            case "tunnel_data":
                // 保存隧道上下文
                pendingTunnelMsgId = (String) msg.get("msgId");
                pendingTunnelBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (pendingTunnelBodyLen == 0) {
                    // 无body，快速转发空数据
                    handleTunnelData(null);
                }
                break;
            case "heartbeat":
                handleHeartbeat(msg);
                break;
            case "heartbeat_ack":
                heartbeatSent.set(false);
                break;
            default:
                log.warn("未知消息类型: {}", type);
        }
    }

    @Override
    protected void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        byte[] body = new byte[frame.content().readableBytes()];
        frame.content().readBytes(body);

        // 如果有 pendingTunnelMsgId，说明是隧道数据
        if (pendingTunnelMsgId != null) {
            handleTunnelData(body);
            return;
        }

        // 获取当前正在处理的HTTP响应，发送 body 部分
        String currentMsgId = getCurrentMsgId();
        if (currentMsgId != null && body.length > 0) {
            String msgType = getCurrentMsgType();
            boolean isFinished = "http_chunk".equals(msgType) ? isChunkFinished() : chunkFinished;
            requestHandler.sendResponse(currentMsgId, getCurrentStatusCode(),
                    getCurrentHeaders(), body, isFinished);
        }
    }

    private void handleTunnelData(byte[] body) {
        // Worker -> 客户端：转发隧道数据到客户端
        String msgId = pendingTunnelMsgId;
        log.info("收到Worker的tunnel_data: {} bytes, 转发到客户端, msgId={}", body != null ? body.length : 0, msgId);
        requestHandler.sendTunnelDataToClient(msgId, body);
        pendingTunnelMsgId = null;
        pendingTunnelBodyLen = 0;
    }

    private String currentMsgId;
    private String currentMsgType;
    private int currentStatusCode;
    private Map<String, String> currentHeaders;
    private int currentBodyLen;
    private boolean chunkFinished = false;

    // Tunnel 状态
    private String pendingTunnelMsgId;
    private int pendingTunnelBodyLen;

    private String getCurrentMsgId() {
        return currentMsgId;
    }

    private String getCurrentMsgType() {
        return currentMsgType;
    }

    private int getCurrentStatusCode() {
        return currentStatusCode;
    }

    private Map<String, String> getCurrentHeaders() {
        return currentHeaders;
    }

    private boolean isChunkFinished() {
        return chunkFinished;
    }

    private void handleHttpResponse(Map<String, Object> msg) throws Exception {
        currentMsgId = (String) msg.get("msgId");
        currentMsgType = (String) msg.get("type");
        currentStatusCode = ((Number) msg.get("statusCode")).intValue();

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) msg.get("headers");
        currentHeaders = headers;

        currentBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
        chunkFinished = msg.get("finished") != null && (Boolean) msg.get("finished");

        // 从 pending request 中获取原始请求方法，判断是否是 CONNECT
        String method = connectionManager.getPendingRequestMethod(currentMsgId);
        boolean isConnect = "CONNECT".equalsIgnoreCase(method);

        if (currentBodyLen == 0 || isConnect) {
            // 无body 或 CONNECT 请求，直接发送响应
            requestHandler.sendResponse(currentMsgId, currentStatusCode,
                    currentHeaders, null, chunkFinished);

            if (chunkFinished) {
                clearCurrentContext();
            }
        }
        // 有body时，等待binary frame
    }

    private void handleHeartbeat(Map<String, Object> msg) {
        // 收到worker的心跳，发送ack
        try {
            Map<String, Object> ackMap = new HashMap<>();
            ackMap.put("type", "heartbeat_ack");
            ackMap.put("timestamp", System.currentTimeMillis());
            String ack = objectMapper.writeValueAsString(ackMap);
            workerChannel.writeAndFlush(new TextWebSocketFrame(ack));
        } catch (Exception e) {
            log.error("发送心跳响应失败", e);
        }
    }

    private void clearCurrentContext() {
        currentMsgId = null;
        currentMsgType = null;
        currentHeaders = null;
        chunkFinished = false;
    }

    private void startHeartbeat() {
        int interval = config.getWebsocket().getHeartbeatInterval();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (workerChannel != null && workerChannel.isActive()) {
                try {
                    Map<String, Object> pingMap = new HashMap<>();
                    pingMap.put("type", "heartbeat");
                    pingMap.put("timestamp", System.currentTimeMillis());
                    String ping = objectMapper.writeValueAsString(pingMap);
                    workerChannel.writeAndFlush(new TextWebSocketFrame(ping));
                    heartbeatSent.set(true);
                    log.debug("发送心跳到Worker");
                } catch (Exception e) {
                    log.error("发送心跳失败", e);
                }
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket处理器异常", cause);
        ctx.close();
    }
}