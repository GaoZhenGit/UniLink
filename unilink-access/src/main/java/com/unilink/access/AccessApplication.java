package com.unilink.access;

import com.unilink.access.config.AccessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PostConstruct;
import java.util.UUID;

@SpringBootApplication
public class AccessApplication {
    private static final Logger log = LoggerFactory.getLogger(AccessApplication.class);

    @Autowired
    private AccessConfig accessConfig;

    public static void main(String[] args) {
        SpringApplication.run(AccessApplication.class, args);
    }

    @PostConstruct
    public void init() {
        String accessId = accessConfig.getId();
        if (accessId == null || accessId.trim().isEmpty()) {
            accessId = UUID.randomUUID().toString();
            accessConfig.setId(accessId);
        }
        log.info("========== Access ID: {} ==========", accessId);
    }
}
