package com.unilink.access.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;

public class MessageCodec {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final String TYPE_HTTP_REQUEST = "http_request";
    public static final String TYPE_HTTP_RESPONSE = "http_response";
    public static final String TYPE_HTTP_CHUNK = "http_chunk";
    public static final String TYPE_TUNNEL_DATA = "tunnel_data";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_HEARTBEAT_ACK = "heartbeat_ack";

    public static String encode(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Encode failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Decode failed", e);
        }
    }

    public static byte[] decodeBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
