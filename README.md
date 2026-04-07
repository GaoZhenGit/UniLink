# UniLink

内网穿透/HTTP 代理系统，让内网机器能够借助部署在内网集群上的代理服务器，通过外网工作节点访问互联网。

## 系统架构

```
客户端 ──HTTP──► Access ──WebSocket──► Proxy ──WebSocket──► Worker ──HTTP/HTTPS──► 目标服务器
                      (接入端)                    (代理端)                   (工作端)
                        │                          │                          │
                    内网机                      内网集群                    外网机
```

## 功能特性

- 支持 HTTP 和 HTTPS 代理
- 支持 Basic Auth 认证
- 支持 HTTPS CONNECT 隧道
- 支持 SOCKS5 代理协议（默认开启用户名密码认证）
- WebSocket 长连接
- 断线自动重连
- 心跳保活
- 流式响应 (数据即时返回)

## 模块说明

| 模块 | 部署位置 | 功能 |
|------|----------|------|
| unilink-access | 内网机 | 提供本地 HTTP 代理服务，通过 WebSocket 连接代理端 |
| unilink-proxy | 内网集群 | 接入端和工作端的中转站，负责消息路由 |
| unilink-worker | 外网机 | 发起真实 HTTP 请求，访问互联网 |

## 快速开始

### 构建项目

```bash
mvn clean package -DskipTests
```

### 启动服务

1. 先启动代理服务器 (unilink-proxy)
2. 再启动工作节点 (unilink-worker)
3. 最后启动接入端 (unilink-access)

### 测试

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
curl.exe http://localhost:8082/unilink/access/with-history
curl.exe "http://localhost:8082/unilink/access/ac/history?limit=10"
```

**访问历史返回字段：**
```json
{
  "url": "www.baidu.com:443",
  "protocol": "SOCKS5",
  "statusCode": 0,
  "success": true,
  "timestamp": 1743990000000
}
```

## 配置说明

### 接入端 (unilink-access)

配置文件：`unilink-access/src/main/resources/application.yml`

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| access.http.port | 8888 | ACCESS_HTTP_PORT | HTTP 代理端口 |
| access.http.basic-auth.enabled | true | ACCESS_HTTP_BASIC_AUTH_ENABLED | 启用 Basic Auth |
| access.http.basic-auth.username | admin | ACCESS_HTTP_BASIC_AUTH_USERNAME | 用户名 |
| access.http.basic-auth.password | password123 | ACCESS_HTTP_BASIC_AUTH_PASSWORD | 密码 |
| access.socks5.enabled | true | ACCESS_SOCKS5_ENABLED | 启用 SOCKS5 代理 |
| access.socks5.port | 1080 | ACCESS_SOCKS5_PORT | SOCKS5 代理端口 |
| access.socks5.auth.enabled | true | ACCESS_SOCKS5_AUTH_ENABLED | 启用 SOCKS5 用户名密码认证 |
| access.socks5.auth.username | socks5 | ACCESS_SOCKS5_AUTH_USERNAME | SOCKS5 用户名 |
| access.socks5.auth.password | password | ACCESS_SOCKS5_AUTH_PASSWORD | SOCKS5 密码 |
| proxy.host | localhost | PROXY_HOST | 代理端地址 |
| proxy.port | 8889 | PROXY_PORT | 代理端 WebSocket 端口 |
| proxy.ws-path | /unilink/access | PROXY_WS_PATH | WebSocket 路径 |
| proxy.ssl | false | PROXY_SSL | 是否使用 wss |
| proxy.auto-reconnect | true | PROXY_AUTO_RECONNECT | 是否自动重连 |
| proxy.heartbeat-interval | 30 | PROXY_HEARTBEAT_INTERVAL | 心跳间隔(秒) |
| proxy.reconnect.initial-delay | 1000 | PROXY_RECONNECT_INITIAL_DELAY | 初始重连延迟(ms) |
| proxy.reconnect.max-delay | 60000 | PROXY_RECONNECT_MAX_DELAY | 最大重连延迟(ms) |
| proxy.reconnect.multiplier | 2.0 | PROXY_RECONNECT_MULTIPLIER | 重连延迟倍数 |

### 代理端 (unilink-proxy)

配置文件：`unilink-proxy/src/main/resources/application.yml`

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| proxy.websocket.port | 8889 | WebSocket 端口 |
| proxy.websocket.access-path | /unilink/access | 接入端连接路径 |
| proxy.websocket.worker-path | /unilink/worker | 工作端连接路径 |
| proxy.websocket.heartbeat-interval | 30 | 心跳间隔(秒) |
| proxy.websocket.heartbeat-timeout | 60 | 心跳超时(秒) |

### 工作端 (unilink-worker)

配置文件：`unilink-worker/src/main/resources/application.yml`

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| proxy.host | 127.0.0.1 | PROXY_HOST | 代理端地址 |
| proxy.port | 8889 | PROXY_PORT | 代理端 WebSocket 端口 |
| proxy.ws-path | /worker | PROXY_WS_PATH | WebSocket 路径 |
| proxy.ssl | false | PROXY_SSL | 是否使用 wss |
| proxy.auto-reconnect | true | PROXY_AUTO_RECONNECT | 是否自动重连 |
| proxy.heartbeat-interval | 30 | PROXY_HEARTBEAT_INTERVAL | 心跳间隔(秒) |
| proxy.reconnect.initial-delay | 1000 | PROXY_RECONNECT_INITIAL_DELAY | 初始重连延迟(ms) |
| proxy.reconnect.max-delay | 60000 | PROXY_RECONNECT_MAX_DELAY | 最大重连延迟(ms) |
| proxy.reconnect.multiplier | 2.0 | PROXY_RECONNECT_MULTIPLIER | 重连延迟倍数 |
| worker.http.connect-timeout | 30000 | WORKER_HTTP_CONNECT_TIMEOUT | HTTP 连接超时(ms) |
| worker.http.read-timeout | 300000 | WORKER_HTTP_READ_TIMEOUT | HTTP 读取超时(ms) |

## 通信协议

WebSocket 消息使用 JSON + 二进制格式：

- **JSON 头** (文本帧)：包含消息类型、msgId、bodyLen 等元数据
- **二进制 body** (二进制帧)：实际数据内容

消息类型：
- `http_request` - HTTP 请求转发
- `http_response` / `http_chunk` - HTTP 响应
- `tunnel_data` - CONNECT 隧道数据
- `socks5_connect` - SOCKS5 连接请求
- `socks5_response` - SOCKS5 连接响应
- `socks5_tunnel_data` - SOCKS5 隧道数据
- `heartbeat` - 心跳
- `heartbeat_ack` - 心跳响应

## 技术栈

- Spring Boot 2.7.x / Netty 4.x / WebSocket / Maven

## 许可证

MIT
