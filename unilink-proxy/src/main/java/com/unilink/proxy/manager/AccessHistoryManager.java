package com.unilink.proxy.manager;

import com.unilink.proxy.config.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AccessHistoryManager {

    private static final Logger log = LoggerFactory.getLogger(AccessHistoryManager.class);

    private final Map<String, List<AccessHistoryEntry>> historyMap = new ConcurrentHashMap<>();

    @Autowired
    private ProxyConfig config;

    public void recordAccess(String accessId, String url, int statusCode, boolean success) {
        recordAccess(accessId, url, "HTTP", statusCode, success);
    }

    public void recordAccess(String accessId, String url, String protocol, int statusCode, boolean success) {
        if (accessId == null || accessId.isEmpty()) {
            return;
        }

        int maxEntries = config.getHistory().getMaxUrlsPerAccess();
        AccessHistoryEntry entry = new AccessHistoryEntry();
        entry.setAccessId(accessId);
        entry.setUrl(url);
        entry.setProtocol(protocol);
        entry.setStatusCode(statusCode);
        entry.setSuccess(success);
        entry.setTimestamp(System.currentTimeMillis());

        List<AccessHistoryEntry> history = historyMap.computeIfAbsent(accessId, k -> new ArrayList<>());

        synchronized (history) {
            while (history.size() >= maxEntries) {
                history.remove(0);
            }
            history.add(entry);
        }

        log.debug("记录访问历史: accessId={}, protocol={}, url={}, status={}, success={}",
                accessId, protocol, url, statusCode, success);
    }

    public List<AccessHistoryEntry> getHistory(String accessId, int limit) {
        List<AccessHistoryEntry> history = historyMap.get(accessId);
        if (history == null) {
            return Collections.emptyList();
        }

        synchronized (history) {
            int size = history.size();
            if (limit <= 0 || limit > size) {
                limit = size;
            }
            if (limit == 0) {
                return Collections.emptyList();
            }
            return new ArrayList<>(history.subList(size - limit, size));
        }
    }

    public void clearHistory(String accessId) {
        historyMap.remove(accessId);
    }

    public Set<String> getAccessIdsWithHistory() {
        return historyMap.keySet();
    }

    public static class AccessHistoryEntry {
        private String accessId;
        private String url;
        private String protocol;
        private int statusCode;
        private boolean success;
        private long timestamp;

        public String getAccessId() { return accessId; }
        public void setAccessId(String accessId) { this.accessId = accessId; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}
