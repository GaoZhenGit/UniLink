package com.unilink.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "worker")
public class WorkerHttpConfig {

    private Http http = new Http();

    public static class Http {
        private int connectTimeout = 30000;
        private int readTimeout = 300000;

        public int getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
        public int getReadTimeout() { return readTimeout; }
        public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
    }

    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }
}