package com.unilink.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "worker")
public class WorkerConfig {

    private Server server = new Server();
    private Reconnect reconnect = new Reconnect();
    private Http http = new Http();

    public static class Server {
        private String host = "localhost";
        private int port = 8889;
        private String wsPath = "/ws";
        private boolean ssl = false;
        private boolean autoReconnect = true;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getWsPath() { return wsPath; }
        public void setWsPath(String wsPath) { this.wsPath = wsPath; }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean ssl) { this.ssl = ssl; }
        public boolean isAutoReconnect() { return autoReconnect; }
        public void setAutoReconnect(boolean autoReconnect) { this.autoReconnect = autoReconnect; }

        public String getUrl() {
            String scheme = ssl ? "wss://" : "ws://";
            return scheme + host + ":" + port + wsPath;
        }
    }

    public static class Reconnect {
        private int initialDelay = 1000;
        private int maxDelay = 60000;
        private double multiplier = 2.0;

        public int getInitialDelay() { return initialDelay; }
        public void setInitialDelay(int initialDelay) { this.initialDelay = initialDelay; }
        public int getMaxDelay() { return maxDelay; }
        public void setMaxDelay(int maxDelay) { this.maxDelay = maxDelay; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
    }

    public static class Http {
        private int connectTimeout = 30000;
        private int readTimeout = 300000;

        public int getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
        public int getReadTimeout() { return readTimeout; }
        public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
    }

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Reconnect getReconnect() { return reconnect; }
    public void setReconnect(Reconnect reconnect) { this.reconnect = reconnect; }
    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }
}