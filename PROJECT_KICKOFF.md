# ZeroTier Browser — 项目 Kickoff 文档

> 最后更新：2026-06-27 | 当前版本：v1.1.0
> 
> **本文档是项目唯一权威文档，代码、版本号、架构均以本文档为准。**

---

## 1. 项目概述

ZeroTier Browser 是一款 Android 浏览器应用，通过嵌入 ZeroTier 协议栈（libzt）实现浏览器流量经 ZeroTier 虚拟网络路由，从而访问 ZeroTier 子网内的 Web 服务。无需系统级 VPN 权限，应用内即可完成 ZT 组网与代理。

### 核心能力

| 功能 | 说明 |
|------|------|
| ZeroTier 组网 | 内嵌 libzt 原生库，在用户态运行 ZT 协议栈 |
| SOCKS5 代理 | 本地 127.0.0.1:1080，拦截 WebView 流量智能路由 |
| ZT 路由 | 配置的子网目标走 `zts_socket` 转发，其余直连 |
| POST body 支持 | JS Bridge 劫持 fetch/XHR，传递请求体到原生层（v1.1.0+） |
| HTTPS 自签名证书 | ZT 子网内自动信任自签名证书（仅 ZT 子网） |
| 持久化日志 | 崩溃不丢，实时刷盘到文件 + 内存缓冲区 |
| CI/CD | GitHub Actions 自动构建 APK 并发布 Release |

### 设备信息

- 测试设备：realme RMX3706 / Android 16 (API 36) / arm64-v8a
- 网络 ID：`1c33c1ced09a3dc6`（16 进制 → `nwid`）
- ZT 子网：`192.168.192.0/24`（默认配置 `10.147.0.0/16`，用户需改为自己的网段）

---

## 2. 技术架构

```
┌─────────────────────────────────────────────────────┐
│                    MainActivity.kt                   │
│  WebView UI + Toolbar + ZT Status Indicator          │
│  ├─ bodyCollector: RequestBodyCollector (@JavascriptInterface) │
│  └─ ZTWebViewClient (shouldInterceptRequest)         │
│       │  isInZTSubnet?                               │
│       ├─ YES → proxy 127.0.0.1:1080 (SOCKS5)        │
│       └─ NO  → direct system request                 │
└─────────────────────────────────────────────────────┘
                          │
┌─────────────────────────────────────────────────────┐
│                  ZTProxyServer.kt                    │
│  SOCKS5 协议实现（握手/请求解析/双向转发）              │
│  ├─ handleZTConnection() → zts_bsd_read/write 转发   │
│  ├─ tunnelSockets() → 直连路径 Socket.copyTo 转发    │
│  └─ socks5SendResponse / socks5SendResponseForFd     │
└─────────────────────────────────────────────────────┘
                          │
┌─────────────────────────────────────────────────────┐
│                 ZeroTierService.kt                   │
│  libzt 生命周期管理（单例）                             │
│  ├─ ZeroTierNode: init/start/join                   │
│  ├─ 状态轮询: STOPPED → CONNECTING → ONLINE           │
│  ├─ Socket: createSocket/connectSocket/readSocket/   │
│  │        writeSocket/closeSocket/shutdownSocket     │
│  ├─ 日志: 内存缓冲 + 文件持久化 + 轮转                  │
│  └─ 身份: identity.secret/identity.public 保留        │
└─────────────────────────────────────────────────────┘
                          │
┌─────────────────────────────────────────────────────┐
│                   libzt (.aar)                       │
│  ZeroTier 协议栈 C++ 原生实现 → JNI 绑定               │
│  zts_bsd_socket / zts_connect / zts_bsd_read/write   │
└─────────────────────────────────────────────────────┘

JavaScript Bridge（注入到 WebView）：
┌─────────────────────────────────────────────────────┐
│              post_body_hook.js                       │
│  劫持 window.fetch / XMLHttpRequest.prototype.send   │
│  将请求体通过 AndroidBodyCollector.submitBody()        │
│  传给原生层 RequestBodyCollector 缓存                  │
└─────────────────────────────────────────────────────┘
```

### 源文件清单（v1.1.0）

| 文件 | 行数（约） | 职责 |
|------|-----------|------|
| `MainActivity.kt` | ~320 | WebView 浏览器 UI，按钮事件，ZT 状态指示器，JS Bridge 注册 |
| `ZeroTierService.kt` | ~510 | libzt 封装，ZT 节点生命周期，状态轮询，socket 操作，日志 |
| `ZTProxyServer.kt` | ~430 | SOCKS5 代理服务器，ZT/直连路由决策，双向转发 |
| `ZTWebViewClient.kt` | ~230 | WebView 客户端，判断是否走代理，POST body 消费 |
| `RequestBodyCollector.kt` | ~115 | JS Bridge 请求体收集器（@JavascriptInterface） |
| `ZTConfigDialog.kt` | ~185 | 设置弹窗：网络 ID + 子网配置 |
| `post_body_hook.js` | ~95 | JS 注入脚本：劫持 fetch/XHR 收集 POST body |

---

## 3. 构建与 CI/CD

### 仓库

- GitHub: `heiheiheiheiheihei/zerotier-browser`
- 默认分支：`main`
- 构建工具：Gradle + Android SDK

### CI 流程

1. 推送代码到 `main` 分支
2. GitHub Actions 触发 `Build APK & Release` workflow
3. 编译 libzt.aar（从源码，NDK r26）
4. 编译 debug APK（versionCode 自动递增，versionName = `v1.0.N`）
5. 自动创建 Release（tag 格式 `v1.0.N`）
6. 上传 APK 为 Release Asset

### 依赖

```groovy
// libzt - ZeroTier Sockets SDK (用户态协议栈)
implementation(files("libs/libzt.aar"))

// OkHttp - SOCKS5 代理和网络请求
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

---

## 4. 关键设计决策

### 4.1 为何用 libzt 而非系统 VPN

libzt 在用户态运行 ZeroTier 协议栈，不创建内核可见虚拟网卡。好处是不需要 VPN 权限、不需要系统级配置。代价是无法使用系统 Socket API 连接 ZT 子网地址——必须使用 libzt 提供的 `zts_socket()` / `zts_connect()` / `zts_bsd_read()` / `zts_bsd_write()`。

### 4.2 SOCKS5 代理架构

WebView 通过 `shouldInterceptRequest` 判断目标 URL 是否在 ZT 子网范围内。在子网范围内的请求通过 SOCKS5 代理（127.0.0.1:1080）发送：
- **直连路径**：`java.net.Socket` 直接连接
- **ZT 路径**：`zts_bsd_socket()` 创建用户态 fd，通过 `zts_bsd_read()`/`zts_bsd_write()` 与 SOCKS5 客户端手动桥接（参见 v1.1.0 P0-1 修复）

### 4.3 ZT 操作线程模型

所有 libzt 调用必须在单一专用线程上执行（`ztHandler`）。`runOnZtThread()` 通过 Handler + CountDownLatch 实现同步等待，支持可配置超时（默认 10s，socket connect 5s，read/write 30s）。

### 4.4 日志持久化

- 内存缓冲区：最近 500 条日志（`MAX_LOG_ENTRIES = 500`）
- 文件路径：`filesDir/zerotier/zt_log.txt`
- 每条日志立即 `flush()`，崩溃不丢
- 启动时自动将上次日志备份为 `zt_log.prev.txt`
- 日志格式：`[MM-dd HH:mm:ss.SSS] [LEVEL] message`
- writer 赋值与写入由 `writerLock` 保护，无竞态

### 4.5 POST Body JS Bridge

Android `shouldInterceptRequest` 无法获取请求体（平台限制）。解决方案：
1. 页面加载前注入 `post_body_hook.js`
2. JS 劫持 `fetch` 和 `XMLHttpRequest.prototype.send`
3. 请求发出前，JS 将 body（Base64 编码）传给 `@JavascriptInterface`
4. `shouldInterceptRequest` 时按 `(method, url)` 匹配取出 body
5. 同一 `(method, url)` 支持并发（FIFO 队列），5 秒超时自动清理

---

## 5. 问题修复历程

### v1.0.7 → v1.0.13：原生崩溃排查

**症状**：Android 16 arm64-v8a 设备上，`node.join()` 后进程 SIGSEGV。

**排查链**：
1. 日志持久化 → 发现崩溃发生在 Native 层
2. 移除 `node.stop()` 调用（可能清理已释放资源）
3. 每次启动前 `deleteRecursively()` 清理旧数据
4. ZTProxyServer 构造加 try-catch
5. `node.start()` 和 `node.join()` 间加 `Thread.sleep(500)` 延迟

### v1.0.14：根因修复（三重）

| 修复 | 原因 |
|------|------|
| `android:memtagMode="off"` | Android 16 默认启用 MTE，将 libzt 中原本静默的内存越界转为 SIGSEGV |
| ZeroTierNode 创建移到 deleteRecursively 之后 | 避免节点持有已删除目录中文件的引用（Use-After-Free） |
| 延迟 500ms→2000ms + join 前 flush | 确保崩溃日志保存 |

### v1.0.15：全量用户操作日志

所有按钮点击、页面导航、SOCKS5 握手/路由决策、配置变更均记录 `[U]` 级别日志。

### v1.0.16：拒绝假 IP + 连接超时

**问题**：`isOnline()=true`，但 `getIPv4Address()` 返回 `::1`（IPv6 loopback）——ZT 节点连上了根服务器但网络控制器未授权，未分配真实 IP。

| 修复 | 文件 |
|------|------|
| 轮询时拒绝 loopback/零址 IP | ZeroTierService.kt |
| `connectSocket` 超时 10s→5s | ZeroTierService.kt |
| `connectViaZeroTier` 前置检查无真实 IP 立即失败 | ZTProxyServer.kt |

### v1.0.17：网络诊断 Dump

每 15 秒 dump 完整网络状态：`isOnline()`、`getIPv4Address`、`getIPv6Address`、`getMACAddress`、InetAddress class name、canonical host name。

### v1.0.18：修复 MAC 地址轮转

**问题**：每次启动 ZT MAC 地址变化，导致每次都需要在控制器重新授权。

**根因**：`startInternal()` 调用 `ztDir.deleteRecursively()` 清除了整个 ZT 目录，包括 `identity.secret` 和 `identity.public`。

**修复**：选择性清理——仅删除 `planet` / `peers.d` / `moons.d`（v1.1.0 进一步保留 `networks.d`），保留 `identity.public` 和 `identity.secret`。

### v1.1.0：重写 ZT 数据通道 + POST Body 支持（本次发布）

**P0-1：重写 ZT 数据通道**

之前版本用反射将 libzt fd 包装成 `java.net.Socket`（`createSocketFromFd`），在 Android 上必然失败（libzt fd 不是内核 fd，系统调用返回 EBADF），导致 ZT 子网访问永远失败。

**修复**：废弃 `createSocketFromFd`，改用 `zts_bsd_read()`/`zts_bsd_write()` 实现自定义双向转发。新增 `ZeroTierService.readSocket()`/`writeSocket()`/`shutdownSocket()`/`setRecvTimeout()` 封装。

**P0-2：POST/PUT/PATCH Body 支持**

Android `shouldInterceptRequest` 平台限制无法获取请求体，之前版本发空 body。

**修复**：新增 `RequestBodyCollector.kt`（@JavascriptInterface）和 `post_body_hook.js`（注入脚本），劫持 `fetch`/`XMLHttpRequest` 将 body 经 JS Bridge 传给原生层缓存。

**P1-5：HTTPS 自签名证书**

之前版本 `proxyClientInsecure` 仅覆盖 `hostnameVerifier`，未覆盖 `sslSocketFactory`，导致 ZT 子网 HTTPS 自签名证书仍失败。

**修复**：为 `proxyClientInsecure` 注入 trust-all `SSLSocketFactory` + `X509TrustManager`。

**其他修复**：
- P0-3：移除 `node.stop()` 调用（与 v1.0.13 文档一致）
- P1-1：移除 `startInternal` 中重建 `logFileWriter`（消除日志截断）
- P1-2：`writerLock` 保护日志 writer 赋值与写入（消除竞态）
- P1-6：保留 `networks.d` 缓存（MAC 已固定，加速二次启动）
- P2-1：删除 `copyLogToClipboardOnStart` 死代码
- P2-2：`MAX_LOG_ENTRIES` 200 → 500（与文档一致）
- P2-3：`dumpNetworkDiagnostics` 日志 `retry` 误用 `nwid` 修正
- P2-8：`Access-Control-Allow-Origin: *` 仅对 ZT 子网响应注入
- P3：`allowBackup="false"`（防止身份文件被 adb backup 导出）

---

## 6. 当前状态与待办

### 已完成（v1.1.0）

- [x] libzt 集成与 JNI 绑定
- [x] SOCKS5 代理服务器（完整 SOCKS5 协议实现）
- [x] WebView 浏览器 UI（前进/后退/刷新/首页/设置）
- [x] ZT 子网路由决策
- [x] 持久化日志（崩溃不丢）
- [x] Android 16 MTE 兼容性修复
- [x] Native 崩溃修复（UAF + 延迟 join）
- [x] 全量用户操作日志
- [x] 拒绝假 IP（loopback/零址）
- [x] Socket 连接超时可配置
- [x] 网络状态诊断 dump
- [x] MAC 地址固定（身份文件保留）
- [x] ZT 数据通道重写（P0-1，v1.1.0）
- [x] POST/PUT/PATCH body 支持（P0-2，v1.1.0）
- [x] HTTPS 自签名证书信任（P1-5，v1.1.0）
- [x] 日志竞态修复（P1-1/P1-2，v1.1.0）
- [x] networks.d 缓存保留（P1-6，v1.1.0）
- [x] CI/CD 自动构建发布

### 待验证

- [ ] v1.1.0 安装后 ZT 子网 HTTP 访问是否真正可用（P0-1 修复验证）
- [ ] ZT 子网 HTTPS 自签名证书是否可访问（P1-5 修复验证）
- [ ] POST/PUT/PATCH body 是否完整传递（P0-2 修复验证）
- [ ] FormData/Blob body 场景是否正常降级（不崩溃）
- [ ] 长时间运行稳定性（内存、连接泄漏）
- [ ] `targetSdk` 升级到 36 并回归测试

### 已知限制（v1.1.0）

- **FormData/Blob**：JS 劫持无法同步序列化 FormData 和 Blob body，这两类 POST body 会丢失（返回空 body，不崩溃）
- **POST body 大小**：JS Bridge 传递的请求体上限 5MB，超大会被丢弃
- **无 planet 文件**：编译时使用 libzt 内置默认根服务器。如果需要自定义根服务器，需要将 `planet` 文件放入 `assets/` 目录
- **单网络**：当前仅支持加入一个 ZeroTier 网络
- **debug 构建**：CI 构建的是 debug APK，未做混淆/优化
- **仅 IPv4 ZT 路径**：当前 ZT 路径的 SOCKS5 响应仅构建 IPv4 地址类型（直连路径支持 IPv6）

---

## 7. 部署与使用

### 首次使用步骤

1. 从 GitHub Release 下载 APK 安装
2. 打开应用 → 点击右上角设置按钮
3. 填入 ZeroTier Network ID（16 位十六进制）
4. 填入子网 CIDR（如 `192.168.192.0/24`）
5. 保存配置
6. 应用自动启动 ZeroTier 连接
7. **首次使用时**：在 ZeroTier Central 面板授权此设备的 MAC 地址
8. 状态指示器变绿（ONLINE）后即可访问 ZT 子网内服务

### 状态指示器

| 颜色 | 状态 | 含义 |
|------|------|------|
| 灰色 | STOPPED | 未启动 |
| 黄色 | CONNECTING | 正在连接 ZT 网络 |
| 绿色 | ONLINE | 已上线，有真实 IP |
| 红色 | OFFLINE | 连接超时或失败 |

### 日志查看

点击设置弹窗中的「Copy Log」按钮，将完整日志复制到剪贴板。

---

## 8. 开发指南

### 代码修改后推送流程

```bash
# 1. 修改代码
# 2. 提交到本地 main 分支
git add -A
git commit -m "fix: <描述>"
# 3. 推送到远程 main 分支（需要 repo 写权限）
git push origin main
# 4. GitHub Actions 自动构建 → Release
```

### 关键常量

| 常量 | 值 | 说明 |
|------|------|------|
| `ZT_HOME_DIR` | `zerotier` | ZT 数据目录（相对于 `filesDir`） |
| `PLANET_FILE` | `planet` | 根服务器配置文件名 |
| `SOCKS5_PORT` | `1080` | 本地代理端口 |
| `POLL_TIMEOUT` | `60s` | ONLINE 状态轮询超时 |
| `CONNECT_TIMEOUT` | `5s` | zts_connect 超时 |
| `JOIN_DELAY` | `2s` | node.start() 后等待再 join() |
| `MAX_LOG_ENTRIES` | `500` | 内存日志缓冲区上限 |
| `POST_BODY_TTL_MS` | `5000` | JS Bridge body 缓存 TTL |
| `POST_BODY_MAX_SIZE` | `5MB` | JS Bridge body 大小上限 |

### 线程安全注意事项

1. 所有 libzt 调用必须通过 `runOnZtThread()` 在专用 HandlerThread 上执行
2. `log()` 方法通过 `writerLock` 同步写入文件 + 内存缓冲
3. `_status` 使用 `MutableStateFlow`，UI 通过 `observeZeroTierStatus` 安全订阅
4. `activeConnections` 使用 `ConcurrentHashMap`
5. 身份文件备份/恢复在 ZT 线程上执行（`startInternal` 已在该线程）
6. `RequestBodyCollector.store` 使用 `ConcurrentHashMap`，FIFO 队列线程安全

---

## 9. 版本历史

| 版本 | 日期 | 关键变更 |
|------|------|----------|
| v1.0.6 | 2025-xx-xx | 初始版本，基础功能 |
| v1.0.7-v1.0.13 | 2025-xx-xx | 原生崩溃排查 |
| v1.0.14 | 2025-xx-xx | MTE 关闭 + UAF 修复 + 延迟 join |
| v1.0.15 | 2025-xx-xx | 全量用户操作日志 |
| v1.0.16 | 2025-xx-xx | 拒绝假 IP + 连接超时 |
| v1.0.17 | 2025-xx-xx | 网络诊断 Dump |
| v1.0.18 | 2025-xx-xx | MAC 地址固定（身份文件保留） |
| **v1.1.0** | **2026-06-27** | **重写 ZT 数据通道 + POST body 支持 + HTTPS 自签名** |
