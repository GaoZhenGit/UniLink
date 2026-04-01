# CLAUDE.md

本文档为 Claude Code 在本项目中工作时提供指导。

# UniLink 项目

内网穿透/HTTP 代理系统，让内网机器能够借助部署在内网集群上的代理服务器，通过外网工作节点访问互联网。

## 技术栈

- Spring Boot 2.7.x
- Netty 4.x
- WebSocket
- Maven

## 模块

- `unilink-proxy/`: 代理服务器 (部署在内网集群)
  - HTTP 代理端口: 8888
  - WebSocket 端口: 8889
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
3. 内网机器配置 HTTP 代理指向 localhost:8888

## 配置说明

### proxy 配置 (application.yml)

```yaml
proxy:
  http:
    port: 8888           # HTTP 代理端口
    basic-auth:
      enabled: true      # 启用 Basic Auth
      username: admin
      password: password123
  websocket:
    port: 8889           # WebSocket 端口
    heartbeat-interval: 30   # 心跳间隔(秒)
    heartbeat-timeout: 60    # 心跳超时(秒)
```

### worker 配置 (application.yml)

```yaml
proxy:
  url: ws://localhost:8889/ws   # 代理 WebSocket 地址
  auto-reconnect: true

reconnect:
  initial-delay: 1000   # 初始重连延迟(ms)
  max-delay: 30000      # 最大重连延迟(ms)
  multiplier: 1.5       # 延迟倍数

http:
  connect-timeout: 10000   # 连接超时(ms)
  read-timeout: 30000      # 读取超时(ms)
```

## 通信协议

WebSocket 消息类型：
- `http_request`: HTTP 请求转发
- `http_response` / `http_chunk`: HTTP 响应返回
- `tunnel_data`: CONNECT 隧道数据
- `heartbeat` / `heartbeat_ack`: 心跳保活

消息格式：JSON 头 (文本帧) + 二进制 body (二进制帧)

## 源代码结构

```
unilink-proxy/src/main/java/com/unilink/proxy/
├── ProxyApplication.java           # 启动类
├── config/
│   ├── ProxyConfig.java            # 代理配置
│   └── WsServerConfig.java         # WebSocket 配置
├── handler/
│   └── ProxyRequestHandler.java    # 请求处理
├── server/
│   ├── HttpProxyServer.java        # HTTP 代理服务器 (Netty)
│   ├── HttpProxyChannelHandler.java # 通道处理器 (含 CONNECT 隧道)
│   └── WorkerConnectionManager.java # Worker 连接管理
└── websocket/
    ├── MessageCodec.java           # 消息编解码
    ├── ProxyWebSocketHandler.java  # WebSocket 处理器
    └── TextWebSocketFrameHandler.java

unilink-worker/src/main/java/com/unilink/worker/
├── WorkerApplication.java          # 启动类
├── config/
│   └── WorkerConfig.java           # 工作节点配置
├── client/
│   └── WorkerWebSocketClient.java  # WebSocket 客户端
├── http/
│   └── RealHttpClient.java         # HTTP 客户端
├── protocol/
│   └── MessageHandler.java         # 消息处理
└── tunnel/
    └── ConnectTunnelHandler.java   # CONNECT 隧道处理
```

## 测试方法

```bash
# HTTP 测试
curl -x http://localhost:8888 -U admin:password123 -v http://httpbin.org/get

# HTTPS 测试
curl -x http://localhost:8888 -U admin:password123 -v https://httpbin.org/get
```