package com.unilink.access.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.access.config.AccessConfig;
import com.unilink.access.config.AccessProxyConfig;
import com.unilink.access.server.HttpRequestHandler;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AccessWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(AccessWebSocketClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AccessProxyConfig config;
    @Autowired
    private AccessConfig accessConfig;

    @Autowired
    private HttpRequestHandler requestHandler;

    private WebSocketSession session;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledExecutorService reconnectScheduler;

    // 消息发送同步锁
    private final Object sendLock = new Object();

    private int currentRetryDelay = 1000;
    private Map<String, Object> pendingMessage;

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

        if (pendingMessage != null) {
            String type = (String) pendingMessage.get("type");
            if ("tunnel_data".equals(type)) {
                requestHandler.sendTunnelDataToClient((String) pendingMessage.get("msgId"), body);
            } else {
                handleHttpResponse(pendingMessage, body);
            }
            pendingMessage = null;
        }
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
