package com.unilink.access.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "access")
public class AccessConfig {

    private Http http = new Http();
    private Proxy proxy = new Proxy();

    public static class Http {
        private int port = 8888;
        private BasicAuth basicAuth = new BasicAuth();

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public BasicAuth getBasicAuth() { return basicAuth; }
        public void setBasicAuth(BasicAuth basicAuth) { this.basicAuth = basicAuth; }
    }

    public static class BasicAuth {
        private boolean enabled = true;
        private String username = "admin";
        private String password = "password123";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Proxy {
        private String host = "localhost";
        private int port = 8889;
        private String wsPath = "/access";
        private boolean ssl = false;
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
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public Reconnect getReconnect() { return reconnect; }
        public void setReconnect(Reconnect reconnect) { this.reconnect = reconnect; }
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

    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }
    public Proxy getProxy() { return proxy; }
    public void setProxy(Proxy proxy) { this.proxy = proxy; }
}
