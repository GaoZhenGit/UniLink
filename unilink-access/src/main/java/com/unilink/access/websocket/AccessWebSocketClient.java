package com.unilink.access.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.access.config.AccessConfig;
import com.unilink.access.config.AccessProxyConfig;
import com.unilink.access.server.HttpRequestHandler;
import com.unilink.access.server.Socks5RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AccessWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(AccessWebSocketClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // WebSocket 帧最大大小 (8KB)，超过则分片
    private static final int MAX_FRAME_SIZE = 8192;

    @Autowired
    private AccessProxyConfig config;
    @Autowired
    private AccessConfig accessConfig;

    @Autowired
    private HttpRequestHandler requestHandler;

    @Autowired
    private Socks5RequestHandler socks5RequestHandler;

    private WebSocketSession session;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledExecutorService reconnectScheduler;

    // 消息发送同步锁
    private final Object sendLock = new Object();

    private int currentRetryDelay = 1000;
    private Map<String, Object> pendingMessage;
    private Map<String, Object> pendingTunnelMessage;
    private Map<String, Object> pendingSocks5Response;

    @PostConstruct
    public void connect() {
        String url = buildWebSocketUrl();
        log.info("连接代理服务器: {}", url);

        try {
            WebSocketClient client = new StandardWebSocketClient();
            final CountDownLatch latch = new CountDownLatch(1);

            client.doHandshake(new TextWebSocketHandler() {

                @Override
                public void afterConnectionEstablished(WebSocketSession session) {
                    connected.set(true);
                    AccessWebSocketClient.this.session = session;
                    log.info("已连接到代理服务器");
                    sendRegisterMessage();
                    currentRetryDelay = config.getReconnect().getInitialDelay();
                    startHeartbeat();
                    latch.countDown();
                }

                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                    try {
                        handleTextMessagePayload(message.getPayload());
                    } catch (Exception e) {
                        log.error("处理文本消息失败", e);
                    }
                }

                @Override
                protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                    try {
                        handleBinaryMessagePayload(message);
                    } catch (Exception e) {
                        log.error("处理二进制消息失败", e);
                    }
                }

                @Override
                public void handleTransportError(WebSocketSession session, Throwable exception) {
                    log.error("WebSocket传输错误", exception);
                    onDisconnected();
                }

                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
                    log.info("WebSocket连接关闭: {}", closeStatus);
                    onDisconnected();
                }
            }, url).get(10, TimeUnit.SECONDS);

            log.info("WebSocket连接已建立");
        } catch (Exception e) {
            log.error("连接代理服务器失败: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private String buildWebSocketUrl() {
        String scheme = config.isSsl() ? "wss" : "ws";
        return String.format("%s://%s:%d%s",
                scheme,
                config.getHost(),
                config.getPort(),
                config.getWsPath());
    }

    @PreDestroy
    public void disconnect() {
        connected.set(false);
        stopHeartbeat();
        stopReconnect();
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (Exception e) {
                log.error("关闭连接失败", e);
            }
        }
    }

    private void onDisconnected() {
        connected.set(false);
        stopHeartbeat();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (currentRetryDelay < config.getReconnect().getInitialDelay()) {
            currentRetryDelay = config.getReconnect().getInitialDelay();
        }
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdown();
        }
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
        log.debug("将在 {}ms 后尝试重连", currentRetryDelay);
        reconnectScheduler.schedule(() -> connect(), currentRetryDelay, TimeUnit.MILLISECONDS);
        currentRetryDelay = (int) Math.min(
                currentRetryDelay * config.getReconnect().getMultiplier(),
                config.getReconnect().getMaxDelay()
        );
    }

    private void stopReconnect() {
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdown();
        }
    }

    private void startHeartbeat() {
        int interval = config.getHeartbeatInterval();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (connected.get() && session != null && session.isOpen()) {
                try {
                    Map<String, Object> pingMap = new HashMap<>();
                    pingMap.put("type", "heartbeat");
                    pingMap.put("timestamp", System.currentTimeMillis());
                    String ping = objectMapper.writeValueAsString(pingMap);
                    sendMessageSync(new TextMessage(ping));
                    log.debug("发送心跳到Proxy");
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

    private void sendRegisterMessage() {
        try {
            Map<String, Object> registerMsg = new HashMap<>();
            registerMsg.put("type", "register");
            registerMsg.put("accessId", accessConfig.getId());
            registerMsg.put("timestamp", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(registerMsg);
            sendMessageSync(new TextMessage(json));
            log.info("已发送注册消息，accessId={}", accessConfig.getId());
        } catch (Exception e) {
            log.error("发送注册消息失败", e);
        }
    }

    private void handleTextMessagePayload(String text) throws Exception {
        log.debug("收到消息: {}", text);
        Map<String, Object> msg = objectMapper.readValue(text, Map.class);
        String type = (String) msg.get("type");

        switch (type) {
            case "http_response":
            case "http_chunk":
                pendingMessage = msg;
                int bodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (bodyLen == 0) {
                    handleHttpResponse(msg, null);
                }
                break;
            case "tunnel_data":
                pendingMessage = msg;
                int tunnelBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (tunnelBodyLen == 0) {
                    requestHandler.sendTunnelDataToClient((String) msg.get("msgId"), null);
                }
                break;
            case "socks5_response":
                // SOCKS5 连接响应（使用独立变量，避免被 tunnel_data 覆盖）
                pendingSocks5Response = msg;
                int socks5RespLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (socks5RespLen == 0) {
                    handleSocks5Response(msg, null);
                    pendingSocks5Response = null;
                }
                break;
            case "socks5_tunnel_data":
                // SOCKS5 隧道数据
                pendingTunnelMessage = msg;
                int socks5TunnelLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (socks5TunnelLen == 0) {
                    socks5RequestHandler.handleTunnelData((String) msg.get("msgId"), null);
                }
                break;
            case "heartbeat":
                handleHeartbeat();
                break;
            case "heartbeat_ack":
                log.debug("收到心跳响应");
                break;
            default:
                log.debug("未知消息类型: {}", type);
        }
    }

    private void handleBinaryMessagePayload(BinaryMessage message) throws Exception {
        byte[] body = message.getPayload().array();

        // SOCKS5 响应优先处理（使用独立变量，不会被 tunnel_data 覆盖）
        if (pendingSocks5Response != null) {
            handleSocks5Response(pendingSocks5Response, body);
            pendingSocks5Response = null;
        } else if (pendingTunnelMessage != null) {
            // SOCKS5 隧道数据
            socks5RequestHandler.handleTunnelData((String) pendingTunnelMessage.get("msgId"), body);
            pendingTunnelMessage = null;
        } else if (pendingMessage != null) {
            String type = (String) pendingMessage.get("type");
            if ("tunnel_data".equals(type)) {
                requestHandler.sendTunnelDataToClient((String) pendingMessage.get("msgId"), body);
            } else {
                handleHttpResponse(pendingMessage, body);
            }
            pendingMessage = null;
        }
    }

    private void handleSocks5Response(Map<String, Object> msg, byte[] body) {
        String msgId = (String) msg.get("msgId");
        int status = msg.get("status") != null ? ((Number) msg.get("status")).intValue() : 0;
        socks5RequestHandler.handleSocks5Response(msgId, status, null);
    }

    @SuppressWarnings("unchecked")
    private void handleHttpResponse(Map<String, Object> msg, byte[] body) {
        String msgId = (String) msg.get("msgId");
        int statusCode = msg.get("statusCode") != null ? ((Number) msg.get("statusCode")).intValue() : 200;
        Map<String, String> headers = msg.get("headers") != null ? (Map<String, String>) msg.get("headers") : null;
        boolean finished = msg.get("finished") != null && (Boolean) msg.get("finished");

        requestHandler.sendResponse(msgId, statusCode, headers, body, finished);
    }

    private void handleHeartbeat() {
        try {
            Map<String, Object> ackMap = new HashMap<>();
            ackMap.put("type", "heartbeat_ack");
            ackMap.put("timestamp", System.currentTimeMillis());
            String ack = objectMapper.writeValueAsString(ackMap);
            sendMessageSync(new TextMessage(ack));
        } catch (Exception e) {
            log.error("发送心跳响应失败", e);
        }
    }

    /**
     * 同步发送消息，避免并发冲突
     */
    private void sendMessageSync(TextMessage message) throws Exception {
        synchronized (sendLock) {
            if (session != null && session.isOpen()) {
                session.sendMessage(message);
            }
        }
    }

    /**
     * 同步发送二进制消息
     */
    private void sendBinaryMessageSync(BinaryMessage message) throws Exception {
        synchronized (sendLock) {
            if (session != null && session.isOpen()) {
                session.sendMessage(message);
            }
        }
    }

    /**
     * 同时发送 JSON 元数据和二进制数据，自动处理大数据的分片
     * 如果二进制数据超过 MAX_FRAME_SIZE，则自动分片发送
     * @param json JSON 元数据字符串
     * @param binaryData 二进制数据，可以为 null 或空
     */
    public void sendMessageWithBody(String json, byte[] binaryData) throws Exception {
        synchronized (sendLock) {
            if (session == null || !session.isOpen()) {
                throw new RuntimeException("WebSocket未连接");
            }

            if (binaryData == null || binaryData.length == 0) {
                // 无二进制数据，直接发送 JSON
                session.sendMessage(new TextMessage(json));
                return;
            }

            if (binaryData.length <= MAX_FRAME_SIZE) {
                // 不需要分片，直接发送
                session.sendMessage(new TextMessage(json));
                session.sendMessage(new BinaryMessage(ByteBuffer.wrap(binaryData)));
            } else {
                // 需要分片
                String fragId = UUID.randomUUID().toString();
                int fragCount = (binaryData.length + MAX_FRAME_SIZE - 1) / MAX_FRAME_SIZE;

                log.debug("Access开始分片发送: fragId={}, totalSize={}, fragCount={}", fragId, binaryData.length, fragCount);

                int offset = 0;
                int seqIdx = 0;

                while (offset < binaryData.length) {
                    int remaining = binaryData.length - offset;
                    int chunkSize = Math.min(remaining, MAX_FRAME_SIZE);

                    // 创建分片元数据
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fragMetadata = objectMapper.readValue(json, Map.class);
                    fragMetadata.put("fragId", fragId);
                    fragMetadata.put("seqIdx", seqIdx);
                    fragMetadata.put("fragCount", fragCount);
                    fragMetadata.put("bodyLen", chunkSize);

                    String fragJson = objectMapper.writeValueAsString(fragMetadata);

                    // 提取分片数据
                    byte[] chunk = new byte[chunkSize];
                    System.arraycopy(binaryData, offset, chunk, 0, chunkSize);

                    // 发送分片：先发 JSON 元数据，再发二进制分片
                    session.sendMessage(new TextMessage(fragJson));
                    session.sendMessage(new BinaryMessage(ByteBuffer.wrap(chunk)));

                    offset += chunkSize;
                    seqIdx++;

                    log.debug("Access发送分片: fragId={}, seqIdx={}/{}", fragId, seqIdx, fragCount);
                }
            }
        }
    }

    public void sendMessage(String text, byte[] binaryData) throws Exception {
        synchronized (sendLock) {
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(text));
                if (binaryData != null && binaryData.length > 0) {
                    session.sendMessage(new BinaryMessage(ByteBuffer.wrap(binaryData)));
                }
            } else {
                throw new RuntimeException("WebSocket未连接");
            }
        }
    }

    public void sendMessage(String text) throws Exception {
        sendMessageSync(new TextMessage(text));
    }

    public void sendBinaryMessage(byte[] data) throws Exception {
        sendBinaryMessageSync(new BinaryMessage(ByteBuffer.wrap(data)));
    }

    public boolean isConnected() {
        return connected.get() && session != null && session.isOpen();
    }
}
