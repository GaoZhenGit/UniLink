package com.unilink.proxy.controller;

import com.unilink.proxy.manager.SessionRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/unilink")
public class StatusController {

    @Autowired
    private SessionRouter sessionRouter;

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("accessCount", sessionRouter.getAccessCount());
        result.put("workerCount", sessionRouter.getWorkerCount());
        result.put("accessList", sessionRouter.getAccessList());
        result.put("workerList", sessionRouter.getWorkerList());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @GetMapping("/access/{id}")
    public Map<String, Object> getAccessById(@PathVariable String id) {
        Map<String, Object> info = sessionRouter.getAccessInfo(id);
        if (info == null) {
            return null;
        }
        return info;
    }

    @GetMapping("/worker/{id}")
    public Map<String, Object> getWorkerById(@PathVariable String id) {
        Map<String, Object> info = sessionRouter.getWorkerInfo(id);
        if (info == null) {
            return null;
        }
        return info;
    }
}
