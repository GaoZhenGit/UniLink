package com.unilink.worker.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unilink.worker.client.WorkerWebSocketClient;
import com.unilink.worker.config.WorkerConfig;
import com.unilink.worker.http.RealHttpClient;
import com.unilink.worker.tunnel.ConnectTunnelHandler;
import com.unilink.worker.tunnel.Socks5TunnelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Lazy
    private WorkerWebSocketClient wsClient;

    @Autowired
    private RealHttpClient httpClient;

    @Autowired
    private ConnectTunnelHandler tunnelHandler;

    @Autowired
    private Socks5TunnelHandler socks5TunnelHandler;

    public void handleHttpRequest(Map<String, Object> msg, byte[] body) {
        try {
            String msgId = (String) msg.get("msgId");
            String method = (String) msg.get("method");
            String url = (String) msg.get("url");

            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) msg.get("headers");

            log.info("收到HTTP请求: {} {} (msgId={})", method, url, msgId);

            // CONNECT 请求使用隧道处理
            if ("CONNECT".equalsIgnoreCase(method)) {
                String[] parts = url.split(":");
                String host = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;
                tunnelHandler.handleConnect(msgId, host, port);
            } else {
                httpClient.executeRequest(msgId, method, url, headers, body);
            }
        } catch (Exception e) {
            log.error("处理HTTP请求失败", e);
        }
    }

    public void handleBinaryResponse(byte[] body) {
        log.debug("收到二进制响应, size={}", body.length);
    }

    public void handleTunnelData(String msgId, byte[] body) {
        tunnelHandler.handleTunnelData(msgId, body);
    }

    /**
     * 处理 SOCKS5 连接请求
     */
    public void handleSocks5Connect(Map<String, Object> msg) {
        try {
            String msgId = (String) msg.get("msgId");
            String username = (String) msg.get("username");
            String host = (String) msg.get("host");
            int port = msg.get("port") != null ? ((Number) msg.get("port")).intValue() : 443;

            log.info("收到SOCKS5请求: {}:{} (msgId={}, username={})", host, port, msgId, username);
            socks5TunnelHandler.handleSocks5Connect(msgId, username, host, port);
        } catch (Exception e) {
            log.error("处理SOCKS5请求失败", e);
        }
    }

    /**
     * 处理 SOCKS5 隧道数据
     */
    public void handleSocks5TunnelData(String msgId, byte[] body) {
        socks5TunnelHandler.handleTunnelData(msgId, body);
    }
}