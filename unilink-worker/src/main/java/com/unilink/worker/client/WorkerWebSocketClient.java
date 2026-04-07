package com.unilink.worker.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.worker.config.WorkerConfig;
import com.unilink.worker.config.WorkerProxyConfig;
import com.unilink.worker.http.RealHttpClient;
import com.unilink.worker.protocol.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import javax.annotation.PostConstruct;
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
public class WorkerWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerWebSocketClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WorkerProxyConfig config;

    @Autowired
    private WorkerConfig workerConfig;

    @Autowired
    @Lazy
    private RealHttpClient httpClient;

    @Autowired
    private MessageHandler messageHandler;

    private WebSocketSession session;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledExecutorService reconnectScheduler;

    // 消息发送同步锁
    private final Object sendLock = new Object();

    private int currentRetryDelay = 1000; // 默认1秒
    private Map<String, Object> pendingHttpRequest;
    private Map<String, Object> pendingTunnelRequest;

    @PostConstruct
    public void connect() {
        String url = config.getUrl();
        log.info("连接代理服务器: {}", url);

        try {
            WebSocketClient client = new StandardWebSocketClient();
            final CountDownLatch latch = new CountDownLatch(1);

            client.doHandshake(new TextWebSocketHandler() {

                @Override
                public void afterConnectionEstablished(WebSocketSession session) {
                    connected.set(true);
                    WorkerWebSocketClient.this.session = session;
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

        } catch (Exception e) {
            log.error("连接代理服务器失败: {}", e.getMessage());
            scheduleReconnect();
        }
    }

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
        if (config.isAutoReconnect()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        // 确保最小延迟
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
            registerMsg.put("workerId", workerConfig.getId());
            registerMsg.put("timestamp", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(registerMsg);
            sendMessageSync(new TextMessage(json));
            log.info("已发送注册消息，workerId={}", workerConfig.getId());
        } catch (Exception e) {
            log.error("发送注册消息失败", e);
        }
    }

    private void handleTextMessagePayload(String text) throws Exception {
        log.debug("收到消息: {}", text);
        Map<String, Object> msg = objectMapper.readValue(text, Map.class);
        String type = (String) msg.get("type");

        switch (type) {
            case "http_request":
                pendingHttpRequest = msg;
                int bodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (bodyLen == 0) {
                    messageHandler.handleHttpRequest(msg, new byte[0]);
                }
                break;
            case "tunnel_data":
                // HTTP CONNECT 隧道数据
                pendingHttpRequest = msg;
                int tunnelBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (tunnelBodyLen == 0) {
                    messageHandler.handleTunnelData((String) msg.get("msgId"), new byte[0]);
                }
                break;
            case "socks5_connect":
                // SOCKS5 连接请求
                pendingTunnelRequest = msg;
                int socks5BodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (socks5BodyLen == 0) {
                    messageHandler.handleSocks5Connect(msg);
                }
                break;
            case "socks5_tunnel_data":
                // SOCKS5 隧道数据
                pendingTunnelRequest = msg;
                int socks5TunnelBodyLen = msg.get("bodyLen") != null ? ((Number) msg.get("bodyLen")).intValue() : 0;
                if (socks5TunnelBodyLen == 0) {
                    messageHandler.handleSocks5TunnelData((String) msg.get("msgId"), new byte[0]);
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

        // 检查是否有待处理的 SOCKS5 隧道数据
        if (pendingTunnelRequest != null) {
            String type = (String) pendingTunnelRequest.get("type");
            String msgId = (String) pendingTunnelRequest.get("msgId");

            if ("socks5_connect".equals(type)) {
                messageHandler.handleSocks5Connect(pendingTunnelRequest);
            } else if ("socks5_tunnel_data".equals(type)) {
                messageHandler.handleSocks5TunnelData(msgId, body);
            }
            pendingTunnelRequest = null;
        } else if (pendingHttpRequest != null) {
            String type = (String) pendingHttpRequest.get("type");
            String msgId = (String) pendingHttpRequest.get("msgId");

            if ("tunnel_data".equals(type)) {
                messageHandler.handleTunnelData(msgId, body);
            } else {
                messageHandler.handleHttpRequest(pendingHttpRequest, body);
            }
            pendingHttpRequest = null;
        } else {
            messageHandler.handleBinaryResponse(body);
        }
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

    public void sendMessage(String text) throws Exception {
        sendMessageSync(new TextMessage(text));
    }

    public void sendBinaryMessage(byte[] data) throws Exception {
        sendBinaryMessageSync(new BinaryMessage(ByteBuffer.wrap(data)));
    }

    public boolean isConnected() {
        return connected.get();
    }
}
