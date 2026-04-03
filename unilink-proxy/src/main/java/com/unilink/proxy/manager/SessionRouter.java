package com.unilink.proxy.manager;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息路由器 - 负责在 access 和 worker 之间转发消息
 */
@Component
public class SessionRouter {

    private static final Logger log = LoggerFactory.getLogger(SessionRouter.class);

    // access 连接池: channelId -> channel
    private final Map<String, Channel> accessChannels = new ConcurrentHashMap<>();

    // worker 连接池: channelId -> channel
    private final Map<String, Channel> workerChannels = new ConcurrentHashMap<>();

    // access-worker 绑定关系
    private final Map<String, String> accessToWorker = new ConcurrentHashMap<>();
    private final Map<String, String> workerToAccess = new ConcurrentHashMap<>();

    // 待转发的二进制数据上下文
    private final Map<String, String> pendingBinaryTarget = new ConcurrentHashMap<>();

    // 轮询索引
    private int roundRobinIndex = 0;

    /**
     * 注册 access 连接
     */
    public void registerAccess(Channel channel) {
        String accessId = getChannelId(channel);
        accessChannels.put(accessId, channel);
        log.info("Access连接注册: {}, 当前数量: {}", accessId, accessChannels.size());
    }

    /**
     * 移除 access 连接
     */
    public void unregisterAccess(Channel channel) {
        String accessId = getChannelId(channel);
        accessChannels.remove(accessId);

        // 解除绑定
        String workerId = accessToWorker.remove(accessId);
        if (workerId != null) {
            workerToAccess.remove(workerId);
        }

        log.info("Access连接断开: {}, 当前数量: {}", accessId, accessChannels.size());
    }

    /**
     * 注册 worker 连接
     */
    public void registerWorker(Channel channel) {
        String workerId = getChannelId(channel);
        workerChannels.put(workerId, channel);
        log.info("Worker连接注册: {}, 当前数量: {}", workerId, workerChannels.size());
    }

    /**
     * 移除 worker 连接
     */
    public void unregisterWorker(Channel channel) {
        String workerId = getChannelId(channel);
        workerChannels.remove(workerId);

        // 解除绑定
        String accessId = workerToAccess.remove(workerId);
        if (accessId != null) {
            accessToWorker.remove(accessId);
        }

        log.info("Worker连接断开: {}, 当前数量: {}", workerId, workerChannels.size());
    }

    /**
     * 从 access 转发文本消息到 worker
     */
    public void forwardTextToWorker(Channel accessChannel, String message) {
        String accessId = getChannelId(accessChannel);
        Channel workerChannel = getWorkerForAccess(accessId);

        if (workerChannel != null && workerChannel.isActive()) {
            workerChannel.writeAndFlush(new TextWebSocketFrame(message));
            log.debug("转发文本消息 Access({}) -> Worker({})", accessId, getChannelId(workerChannel));
        } else {
            log.warn("没有可用的Worker连接: accessId={}", accessId);
        }
    }

    /**
     * 从 worker 转发文本消息到 access
     */
    public void forwardTextToAccess(Channel workerChannel, String message) {
        String workerId = getChannelId(workerChannel);
        String accessId = workerToAccess.get(workerId);

        if (accessId != null) {
            Channel accessChannel = accessChannels.get(accessId);
            if (accessChannel != null && accessChannel.isActive()) {
                accessChannel.writeAndFlush(new TextWebSocketFrame(message));
                log.debug("转发文本消息 Worker({}) -> Access({})", workerId, accessId);
            }
        } else {
            log.warn("Worker未绑定Access: workerId={}", workerId);
        }
    }

    /**
     * 从 access 转发二进制数据到 worker
     */
    public void forwardBinaryToWorker(Channel accessChannel, byte[] data) {
        String accessId = getChannelId(accessChannel);
        Channel workerChannel = getWorkerForAccess(accessId);

        if (workerChannel != null && workerChannel.isActive()) {
            workerChannel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
            log.debug("转发二进制数据 Access({}) -> Worker({}), len={}", accessId, getChannelId(workerChannel), data.length);
        }
    }

    /**
     * 从 worker 转发二进制数据到 access
     */
    public void forwardBinaryToAccess(Channel workerChannel, byte[] data) {
        String workerId = getChannelId(workerChannel);
        String accessId = workerToAccess.get(workerId);

        if (accessId != null) {
            Channel accessChannel = accessChannels.get(accessId);
            if (accessChannel != null && accessChannel.isActive()) {
                accessChannel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
                log.debug("转发二进制数据 Worker({}) -> Access({}), len={}", workerId, accessId, data.length);
            }
        }
    }

    /**
     * 设置下一帧二进制数据的目标
     */
    public void setPendingBinaryTarget(String fromChannelId, String direction) {
        pendingBinaryTarget.put(fromChannelId, direction);
    }

    /**
     * 获取并清除待转发的二进制目标
     */
    public String getAndClearPendingBinaryTarget(String fromChannelId) {
        return pendingBinaryTarget.remove(fromChannelId);
    }

    /**
     * 获取 access 对应的 worker（如果没有绑定则自动选择一个）
     */
    private Channel getWorkerForAccess(String accessId) {
        String workerId = accessToWorker.get(accessId);

        if (workerId != null) {
            Channel worker = workerChannels.get(workerId);
            if (worker != null && worker.isActive()) {
                return worker;
            }
            // 绑定的 worker 已断开，清除绑定
            accessToWorker.remove(accessId);
            workerToAccess.remove(workerId);
        }

        // 选择一个可用的 worker
        workerId = findAvailableWorker();
        if (workerId != null) {
            accessToWorker.put(accessId, workerId);
            workerToAccess.put(workerId, accessId);
            return workerChannels.get(workerId);
        }

        return null;
    }

    /**
     * 查找可用的 worker（轮询方式）
     */
    private String findAvailableWorker() {
        if (workerChannels.isEmpty()) {
            return null;
        }

        synchronized (this) {
            int size = workerChannels.size();
            int attempts = 0;
            while (attempts < size) {
                roundRobinIndex = (roundRobinIndex + 1) % size;
                String workerId = (String) workerChannels.keySet().toArray()[roundRobinIndex];
                Channel worker = workerChannels.get(workerId);
                if (worker != null && worker.isActive()) {
                    return workerId;
                }
                attempts++;
            }
        }

        return null;
    }

    private String getChannelId(Channel channel) {
        return channel.id().asShortText();
    }

    public int getAccessCount() {
        return accessChannels.size();
    }

    public int getWorkerCount() {
        return workerChannels.size();
    }
}
