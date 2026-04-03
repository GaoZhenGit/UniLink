package com.unilink.worker;

import com.unilink.worker.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PostConstruct;
import java.util.UUID;

@SpringBootApplication
public class WorkerApplication {
    private static final Logger log = LoggerFactory.getLogger(WorkerApplication.class);

    @Autowired
    private WorkerConfig workerConfig;

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }

    @PostConstruct
    public void init() {
        String workerId = workerConfig.getId();
        if (workerId == null || workerId.trim().isEmpty()) {
            workerId = UUID.randomUUID().toString();
            workerConfig.setId(workerId);
        }
        log.info("========== Worker ID: {} ==========", workerId);
    }
}