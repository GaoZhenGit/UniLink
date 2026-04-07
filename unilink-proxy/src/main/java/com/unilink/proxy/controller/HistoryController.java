package com.unilink.proxy.controller;

import com.unilink.proxy.manager.AccessHistoryManager;
import com.unilink.proxy.manager.SessionRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/unilink/access")
public class HistoryController {

    @Autowired
    private AccessHistoryManager historyManager;

    @Autowired
    private SessionRouter sessionRouter;

    @GetMapping("/{id}/history")
    public Map<String, Object> getHistory(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int limit) {

        Map<String, Object> accessInfo = sessionRouter.getAccessInfo(id);
        if (accessInfo == null) {
            return null;
        }

        List<AccessHistoryManager.AccessHistoryEntry> history =
                historyManager.getHistory(id, limit);

        Map<String, Object> result = new HashMap<>();
        result.put("accessId", id);
        result.put("count", history.size());
        result.put("history", history);
        return result;
    }

    @GetMapping("/with-history")
    public Set<String> getAccessIdsWithHistory() {
        return historyManager.getAccessIdsWithHistory();
    }

    @DeleteMapping("/{id}/history")
    public Map<String, Object> clearHistory(@PathVariable String id) {
        historyManager.clearHistory(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("accessId", id);
        return result;
    }
}
