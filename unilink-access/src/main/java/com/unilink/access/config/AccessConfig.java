package com.unilink.access.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "access")
public class AccessConfig {

    private String id;
    private Http http = new Http();

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

    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}