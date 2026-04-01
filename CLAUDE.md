# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本项目中工作时提供指导。

# UniLink 项目

内网穿透/HTTP 代理系统，让内网机器能够借助部署在内网集群上的代理服务器，通过外网工作节点访问互联网。

## 技术栈

- Spring Boot 2.x
- Netty 4.x
- WebSocket
- Maven

## 模块

- `unilink-proxy/`: 代理服务器 (部署在内网集群)
  - HTTP 代理端口: 8080
  - WebSocket 端口: 9876
  - 支持 Basic Auth 认证
  - 支持 HTTPS CONNECT 隧道
- `unilink-worker/`: 工作节点 (部署在外网机器)
  - 通过 WebSocket 连接到代理服务器
  - 发起真实 HTTP 请求
  - 支持断线自动重连

## 构建

```bash
mvn clean package
```

## 启动

1. 先启动 unilink-proxy (代理服务器)
2. 再启动 unilink-worker (工作节点)
3. 内网机器配置 HTTP 代理指向 proxy:8080

## 通信协议

WebSocket 消息类型：
- `http_request`: HTTP 请求转发
- `http_response`: HTTP 响应返回
- `tunnel_data`: CONNECT 隧道数据
- `heartbeat` / `heartbeat_ack`: 心跳保活

消息格式：JSON 头 (文本帧) + 二进制 body (二进制帧)

## 源代码结构

```
unilink-proxy/src/main/java/com/unilink/proxy/
├── ProxyApplication.java           # 启动类
├── config/                         # 配置类
├── handler/                        # 请求处理
├── server/                         # Netty 服务器
└── websocket/                      # WebSocket 处理

unilink-worker/src/main/java/com/unilink/worker/
├── WorkerApplication.java          # 启动类
├── config/                         # 配置类
├── client/                         # WebSocket 客户端
├── http/                           # HTTP 客户端
├── protocol/                       # 消息处理
└── tunnel/                         # CONNECT 隧道
```