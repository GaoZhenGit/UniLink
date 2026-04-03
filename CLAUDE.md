# CLAUDE.md

本文档为 Claude Code 在本项目中工作时提供指导。

> 用户文档、功能特性、配置说明请参考 [README.md](README.md)

## 技术栈

Spring Boot 2.7.x / Netty 4.x / WebSocket / Maven

## 模块

- `unilink-access/`: 接入端 (部署在内网机) - HTTP 代理端口 8888，支持 Basic Auth，通过 WebSocket 连接代理端
- `unilink-proxy/`: 代理端 (部署在内网集群) - WebSocket 端口 8889
- `unilink-worker/`: 工作端 (部署在外网机) - 通过 WebSocket 连接到代理端，发起真实 HTTP 请求

## 构建与启动

### 构建
```bash
mvn clean package -DskipTests
```

### 停止已有进程
**Windows (PowerShell):**
```powershell
jps -l | Select-String unilink | ForEach-Object { ($_ -split '\s+')[0] } | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
```

**Linux:**
```bash
jps -l | grep unilink | awk '{print $1}' | xargs -r kill -9
```

### 后台启动
**Windows (PowerShell):**
```powershell
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-proxy\target\unilink-proxy-{版本号}.jar"
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-worker\target\unilink-worker-{版本号}.jar"
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-access\target\unilink-access-{版本号}.jar"
```

**Linux:**
```bash
nohup java -jar unilink-proxy/target/unilink-proxy-{版本号}.jar > /dev/null 2>&1 &
nohup java -jar unilink-worker/target/unilink-worker-{版本号}.jar > /dev/null 2>&1 &
nohup java -jar unilink-access/target/unilink-access-{版本号}.jar > /dev/null 2>&1 &
```

启动顺序：proxy → worker → access

## 源代码结构

```
unilink-access/src/main/java/com/unilink/access/
├── AccessApplication.java           # 启动类
├── config/
│   ├── AccessConfig.java            # access.http.* 配置
│   └── AccessProxyConfig.java       # proxy.* 连接配置
├── server/
│   ├── HttpProxyServer.java         # HTTP 代理服务器 (Netty)
│   ├── HttpProxyChannelHandler.java # 通道处理器 (含 CONNECT 隧道)
│   ├── HttpRequestHandler.java      # 请求处理
│   └── PendingRequestManager.java   # 请求管理
└── websocket/
    └── AccessWebSocketClient.java   # WebSocket 客户端

unilink-proxy/src/main/java/com/unilink/proxy/
├── ProxyApplication.java            # 启动类
├── config/
│   └── ProxyConfig.java             # 代理配置
└── websocket/
    └── ProxyWebSocketHandler.java   # WebSocket 处理器

unilink-worker/src/main/java/com/unilink/worker/
├── WorkerApplication.java           # 启动类
├── config/
│   ├── WorkerConfig.java            # worker.http.* 配置
│   └── WorkerProxyConfig.java       # proxy.* 连接配置
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
