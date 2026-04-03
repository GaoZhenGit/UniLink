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

- `unilink-access/`: 接入端 (部署在内网机)
  - HTTP 代理端口: 8888
  - 支持 Basic Auth 认证
  - 通过 WebSocket 连接代理端
  - 支持断线自动重连
- `unilink-proxy/`: 代理端 (部署在内网集群)
  - WebSocket 端口: 8889
  - 接入端连接路径: /access
  - 工作端连接路径: /worker
  - 双向心跳保活
- `unilink-worker/`: 工作端 (部署在外网机)
  - 通过 WebSocket 连接到代理端
  - 发起真实 HTTP 请求
  - 支持断线自动重连

## 构建与启动

### 构建
```bash
mvn clean package -DskipTests
```

### 停止已有进程
**Windows (PowerShell):**
```powershell
# 查找并结束所有 unilink 进程
jps -l | Select-String unilink | ForEach-Object { ($_ -split '\s+')[0] } | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
```

**Linux:**
```bash
# 查找并结束所有 unilink 进程
jps -l | grep unilink | awk '{print $1}' | xargs -r kill -9
```

### 后台启动
**Windows (PowerShell):**
```powershell
# 启动 proxy (后台)
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-proxy\target\unilink-proxy-{版本号}.jar"
# 启动 worker (后台)
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-worker\target\unilink-worker-{版本号}.jar"
# 启动 access (后台)
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-access\target\unilink-access-{版本号}.jar"
```

**Linux:**
```bash
# 启动 proxy (后台)
nohup java -jar unilink-proxy/target/unilink-proxy-{版本号}.jar > /dev/null 2>&1 &
# 启动 worker (后台)
nohup java -jar unilink-worker/target/unilink-worker-{版本号}.jar > /dev/null 2>&1 &
# 启动 access (后台)
nohup java -jar unilink-access/target/unilink-access-{版本号}.jar > /dev/null 2>&1 &
```

### 使用说明

1. 先执行构建命令
2. 再执行停止已有进程命令
3. 按顺序启动：proxy → worker → access
4. 内网机器配置 HTTP 代理指向 localhost:8888

## 配置说明

### access 配置 (application.yml)

```yaml
access:
  http:
    port: 8888           # HTTP 代理端口
    basic-auth:
      enabled: true      # 启用 Basic Auth
      username: admin
      password: password123
  server:
    host: localhost      # 代理端地址
    port: 8889           # 代理端 WebSocket 端口
    ws-path: /access     # WebSocket 路径
    ssl: false           # 是否使用 wss
    heartbeat-interval: 30   # 心跳间隔(秒)
    reconnect:
      initial-delay: 1000    # 初始重连延迟(ms)
      max-delay: 60000       # 最大重连延迟(ms)
      multiplier: 2.0        # 延迟倍数
```

### proxy 配置 (application.yml)

```yaml
proxy:
  websocket:
    port: 8889               # WebSocket 端口
    access-path: /access     # 接入端连接路径
    worker-path: /worker     # 工作端连接路径
    heartbeat-interval: 30   # 心跳间隔(秒)
    heartbeat-timeout: 60    # 心跳超时(秒)
```

### worker 配置 (application.yml)

```yaml
worker:
  server:
    host: 127.0.0.1      # 代理端地址
    port: 8889           # 代理端 WebSocket 端口
    ws-path: /worker     # WebSocket 路径
    ssl: false           # 是否使用 wss
    auto-reconnect: true # 启用自动重连
    heartbeat-interval: 30   # 心跳间隔(秒)
  reconnect:
    initial-delay: 1000  # 初始重连延迟(ms)
    max-delay: 60000     # 最大重连延迟(ms)
    multiplier: 2.0      # 延迟倍数
  http:
    connect-timeout: 30000   # 连接超时(ms)
    read-timeout: 300000     # 读取超时(ms)
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
unilink-access/src/main/java/com/unilink/access/
├── AccessApplication.java           # 启动类
├── config/
│   └── AccessConfig.java            # 接入端配置
├── server/
│   ├── HttpProxyServer.java         # HTTP 代理服务器 (Netty)
│   ├── HttpProxyChannelHandler.java # 通道处理器 (含 CONNECT 隧道)
│   ├── HttpRequestHandler.java      # 请求处理
│   └── PendingRequestManager.java   # 请求管理
└── websocket/
    ├── AccessWebSocketClient.java   # WebSocket 客户端
    └── MessageCodec.java            # 消息编解码

unilink-proxy/src/main/java/com/unilink/proxy/
├── ProxyApplication.java            # 启动类
├── config/
│   └── ProxyConfig.java             # 代理配置
├── websocket/
│   └── ProxyWebSocketHandler.java   # WebSocket 处理器
└── ...

unilink-worker/src/main/java/com/unilink/worker/
├── WorkerApplication.java           # 启动类
├── config/
│   └── WorkerConfig.java            # 工作节点配置
├── client/
│   └── WorkerWebSocketClient.java   # WebSocket 客户端
├── http/
│   └── RealHttpClient.java          # HTTP 客户端
├── protocol/
│   └── MessageHandler.java          # 消息处理
└── tunnel/
    └── ConnectTunnelHandler.java    # CONNECT 隧道处理
```

## 测试方法

```powershell
# HTTP 测试
curl.exe -x http://localhost:8888 -U admin:password123 -v http://httpbin.org/get

# HTTPS 测试
curl.exe -x http://localhost:8888 -U admin:password123 -v https://httpbin.org/get
```

## 注意事项
- 如果没有要求，无需启动服务测试
