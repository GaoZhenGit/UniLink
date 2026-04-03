package com.unilink.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "proxy")
public class WorkerProxyConfig {

    private String host = "127.0.0.1";
    private int port = 8889;
    private String wsPath = "/worker";
    private boolean ssl = false;
    private boolean autoReconnect = true;
    private int heartbeatInterval = 30;
    private Reconnect reconnect = new Reconnect();

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
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public Reconnect getReconnect() { return reconnect; }
    public void setReconnect(Reconnect reconnect) { this.reconnect = reconnect; }

    public String getUrl() {
        String scheme = ssl ? "wss://" : "ws://";
        return scheme + host + ":" + port + wsPath;
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
}