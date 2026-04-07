# CLAUDE.md

本文档为 Claude Code 在本项目中工作时提供指导。

> 用户文档、功能特性、配置说明请参考 [README.md](README.md)

## 技术栈

Spring Boot 2.7.x / Netty 4.x / WebSocket / Maven

## 模块

- `unilink-access/`: 接入端 (部署在内网机) - HTTP 代理端口 8888，支持 Basic Auth，SOCKS5 代理端口 1080，通过 WebSocket 连接代理端
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
│   ├── AccessConfig.java            # access.http.* 和 access.socks5.* 配置
│   └── AccessProxyConfig.java       # proxy.* 连接配置
├── server/
│   ├── HttpProxyServer.java         # HTTP 代理服务器 (Netty)
│   ├── HttpProxyChannelHandler.java # 通道处理器 (含 CONNECT 隧道)
│   ├── HttpRequestHandler.java      # HTTP 请求处理
│   ├── Socks5ProxyServer.java       # SOCKS5 代理服务器 (Netty)
│   ├── Socks5ChannelHandler.java    # SOCKS5 协议状态机处理器
│   ├── Socks5RequestHandler.java     # SOCKS5 请求处理
│   ├── Socks5TunnelForwardHandler.java # SOCKS5 隧道数据转发
│   └── PendingRequestManager.java   # 请求管理
└── websocket/
    └── AccessWebSocketClient.java   # WebSocket 客户端

unilink-proxy/src/main/java/com/unilink/proxy/
├── ProxyApplication.java            # 启动类
├── config/
│   └── ProxyConfig.java             # 代理配置
└── websocket/
    ├── ProxyWebSocketHandler.java   # WebSocket 处理器
    ├── AccessHandler.java           # 接入端消息处理
    └── WorkerHandler.java           # 工作端消息处理

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
    ├── ConnectTunnelHandler.java    # CONNECT 隧道处理
    └── Socks5TunnelHandler.java     # SOCKS5 隧道处理
```

## 测试方法

**完整自动化测试（推荐）：**
```powershell
.\test\test.ps1
```
> 脚本自动执行：构建 → 启动服务 → 运行全部测试用例 → 查询历史 → 停止服务

**单独执行测试命令：**
```powershell
# HTTP CONNECT 正常请求
curl.exe -x http://localhost:8888 -U admin:password123 -s -o $null -w "status:%{http_code}" https://httpbin.org/get

# SOCKS5 正常请求（远程 DNS，鉴权默认开启）
curl.exe -x socks5h://127.0.0.1:1080 -U socks5:password -s -o $null -w "status:%{http_code}" https://www.baidu.com

# SOCKS5 无认证（应被拒绝，exit code 97）
curl.exe -x socks5://127.0.0.1:1080 -s -o $null https://www.baidu.com

# DNS 解析失败（应快速失败，约 1s）
curl.exe -x socks5h://127.0.0.1:1080 -U socks5:password -s -o $null https://baidu.co

# 查询访问历史（Proxy HTTP 端口 8082）
curl.exe http://localhost:8082/api/access/with-history
curl.exe "http://localhost:8082/api/access/ac/history?limit=10"
```

## 注意事项

- 本项目允许你自动构建、启动服务，测试，分析日志，排查问题，推荐使用完整测试脚本：
```
.\test\test.ps1    # 自动构建 + 启动 + 运行全部测试用例 + 查询历史 + 停止
```
> 也可手动分步执行：
```
.\test\stop.ps1
del .\logs\*
mvn clean package
.\test\start.ps1
# 等10秒完成ws连接
curl.exe 手动测试...
.\test\stop.ps1
```
