package com.unilink.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {

    private Http http = new Http();
    private Websocket websocket = new Websocket();

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

    public static class Websocket {
        private int port = 8889;
        private String wsPath = "/ws";
        private int heartbeatInterval = 30;
        private int heartbeatTimeout = 60;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getWsPath() { return wsPath; }
        public void setWsPath(String wsPath) { this.wsPath = wsPath; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public int getHeartbeatTimeout() { return heartbeatTimeout; }
        public void setHeartbeatTimeout(int heartbeatTimeout) { this.heartbeatTimeout = heartbeatTimeout; }
    }

    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }
    public Websocket getWebsocket() { return websocket; }
    public void setWebsocket(Websocket websocket) { this.websocket = websocket; }
}