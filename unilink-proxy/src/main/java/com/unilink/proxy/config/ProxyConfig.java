package com.unilink.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {

    private Websocket websocket = new Websocket();
    private History history = new History();

    public static class Websocket {
        private int port = 8889;
        private String accessPath = "/access";
        private String workerPath = "/worker";
        private int heartbeatInterval = 30;
        private int heartbeatTimeout = 60;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getAccessPath() { return accessPath; }
        public void setAccessPath(String accessPath) { this.accessPath = accessPath; }
        public String getWorkerPath() { return workerPath; }
        public void setWorkerPath(String workerPath) { this.workerPath = workerPath; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public int getHeartbeatTimeout() { return heartbeatTimeout; }
        public void setHeartbeatTimeout(int heartbeatTimeout) { this.heartbeatTimeout = heartbeatTimeout; }
    }

    public static class History {
        private int maxEntries = 1000;
        private int maxUrlsPerAccess = 100;

        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
        public int getMaxUrlsPerAccess() { return maxUrlsPerAccess; }
        public void setMaxUrlsPerAccess(int maxUrlsPerAccess) { this.maxUrlsPerAccess = maxUrlsPerAccess; }
    }

    public Websocket getWebsocket() { return websocket; }
    public void setWebsocket(Websocket websocket) { this.websocket = websocket; }
    public History getHistory() { return history; }
    public void setHistory(History history) { this.history = history; }
}
