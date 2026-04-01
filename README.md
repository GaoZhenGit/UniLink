# UniLink

内网穿透/HTTP 代理系统，让内网机器能够借助部署在内网集群上的代理服务器，通过外网工作节点访问互联网。

## 系统架构

```
┌─────────────┐      WebSocket       ┌─────────────┐
│   Worker    │ ◄──────────────────► │   Proxy     │
│ (外网节点)   │                      │ (内网集群)   │
└─────────────┘                      └──────┬──────┘
       │                                      │
       │ HTTP/HTTPS                           │ HTTP 代理
       ▼                                      ▼
┌─────────────┐                      ┌─────────────┐
│  目标服务器  │                      │  内网客户端  │
└─────────────┘                      └─────────────┘
```

## 功能特性

- 支持 HTTP 和 HTTPS 代理
- 支持 Basic Auth 认证
- 支持 HTTPS CONNECT 隧道
- WebSocket 长连接
- 断线自动重连
- 心跳保活

## 快速开始

### 构建项目

```bash
mvn clean package
```

### 启动服务

1. 先启动代理服务器 (unilink-proxy)

2. 再启动工作节点 (unilink-worker)

### 测试

```bash
# HTTP 请求
curl -x http://localhost:8888 -U admin:password123 http://httpbin.org/get

# HTTPS 请求
curl -x http://localhost:8888 -U admin:password123 https://httpbin.org/get
```

## 配置说明

### 代理服务器 (unilink-proxy)

配置文件：`unilink-proxy/src/main/resources/application.yml`

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| proxy.http.port | 8888 | HTTP 代理端口 |
| proxy.http.basic-auth.enabled | true | 启用 Basic Auth |
| proxy.http.basic-auth.username | admin | 用户名 |
| proxy.http.basic-auth.password | password123 | 密码 |
| proxy.websocket.port | 8889 | WebSocket 端口 |

### 工作节点 (unilink-worker)

配置文件：`unilink-worker/src/main/resources/application.yml`

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| proxy.url | ws://localhost:8889/ws | 代理 WebSocket 地址 |
| proxy.auto-reconnect | true | 启用自动重连 |
| reconnect.initial-delay | 1000 | 初始重连延迟(ms) |
| reconnect.max-delay | 30000 | 最大重连延迟(ms) |

## 通信协议

WebSocket 消息使用 JSON + 二进制格式：

- **JSON 头** (文本帧)：包含消息类型、msgId、bodyLen 等元数据
- **二进制 body** (二进制帧)：实际数据内容

消息类型：
- `http_request` - HTTP 请求转发
- `http_response` / `http_chunk` - HTTP 响应
- `tunnel_data` - CONNECT 隧道数据
- `heartbeat` - 心跳
- `heartbeat_ack` - 心跳响应

## 技术栈

- Spring Boot 2.7.x
- Netty 4.x
- WebSocket
- Maven

## 许可证

MIT