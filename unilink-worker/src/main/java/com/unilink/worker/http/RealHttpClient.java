package com.unilink.worker.http;

import com.unilink.worker.client.WorkerWebSocketClient;
import com.unilink.worker.config.WorkerConfig;
import com.unilink.worker.protocol.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RealHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RealHttpClient.class);

    @Autowired
    private WorkerConfig config;

    @Autowired
    private WorkerWebSocketClient wsClient;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public void executeRequest(String msgId, String method, String url,
                               Map<String, String> headers, byte[] body) {
        executor.submit(() -> {
            doExecute(msgId, method, url, headers, body);
        });
    }

    private void doExecute(String msgId, String method, String url,
                           Map<String, String> requestHeaders, byte[] body) {
        HttpURLConnection conn = null;
        try {
            URL targetUrl = new URL(url);
            conn = (HttpURLConnection) targetUrl.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(config.getHttp().getConnectTimeout());
            conn.setReadTimeout(config.getHttp().getReadTimeout());
            conn.setDoOutput(body != null && body.length > 0);
            conn.setRequestProperty("User-Agent", "UniLink-Worker/1.0");

            // 设置headers
            if (requestHeaders != null) {
                final HttpURLConnection finalConn = conn;
                requestHeaders.forEach((key, value) -> {
                    if (key != null && value != null && !"Host".equalsIgnoreCase(key)
                            && !"Content-Length".equalsIgnoreCase(key)) {
                        finalConn.setRequestProperty(key, value);
                    }
                });
            }

            // 发送body
            if (body != null && body.length > 0) {
                conn.setRequestProperty("Content-Length", String.valueOf(body.length));
                conn.getOutputStream().write(body);
            }

            int statusCode = conn.getResponseCode();

            // 收集响应headers
            Map<String, String> responseHeaders = new HashMap<>();
            conn.getHeaderFields().forEach((key, values) -> {
                if (key != null && !values.isEmpty()) {
                    responseHeaders.put(key, String.join(", ", values));
                }
            });

            // 流式读取响应
            InputStream is = conn.getInputStream();
            byte[] buffer = new byte[8192];
            int chunkIndex = 0;
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                boolean finished = is.available() == 0;

                // 发送响应头
                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("msgId", msgId);
                responseMap.put("type", "http_chunk");
                responseMap.put("statusCode", statusCode);
                responseMap.put("headers", responseHeaders);
                responseMap.put("chunkIndex", chunkIndex++);
                responseMap.put("bodyLen", chunk.length);
                responseMap.put("finished", finished);

                wsClient.sendMessage(new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(responseMap));
                wsClient.sendBinaryMessage(chunk);

                if (finished) break;
            }

            log.info("HTTP请求完成: {} {} -> {}", method, url, statusCode);

        } catch (Exception e) {
            log.error("HTTP请求失败: {} {}", method, url, e);
            sendErrorResponse(msgId, 502, e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void sendErrorResponse(String msgId, int statusCode, String message) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("msgId", msgId);
            response.put("type", "http_response");
            response.put("statusCode", statusCode);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/plain");
            response.put("headers", headers);
            response.put("bodyLen", message != null ? message.getBytes().length : 0);
            response.put("finished", true);

            String jsonHeader = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(response);
            wsClient.sendMessage(jsonHeader);

            if (message != null && message.length() > 0) {
                wsClient.sendBinaryMessage(message.getBytes());
            }
        } catch (Exception e) {
            log.error("发送错误响应失败", e);
        }
    }
}