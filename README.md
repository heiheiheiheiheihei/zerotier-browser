---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: bab38ecd087d9eac598933bb9f672232_f61d785670a311f1aabe5254007bceed
    ReservedCode1: AyfnRFXdILon+xwSlxyt1PXFc7r4ASVII438ImydiYhdgXgx2fpngXbdo5174ARwnF9w8ymnqBR9Aqwen693XT8t5U4Pda6sqol7eR6VwzyR94xhytCfTwM0O6fPsz+42Fk+hkicmbz4r/ZJpNCapG7VAaGzcldU9TvyvbOBqW9MynttgVwHvXYkkNI=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: bab38ecd087d9eac598933bb9f672232_f61d785670a311f1aabe5254007bceed
    ReservedCode2: AyfnRFXdILon+xwSlxyt1PXFc7r4ASVII438ImydiYhdgXgx2fpngXbdo5174ARwnF9w8ymnqBR9Aqwen693XT8t5U4Pda6sqol7eR6VwzyR94xhytCfTwM0O6fPsz+42Fk+hkicmbz4r/ZJpNCapG7VAaGzcldU9TvyvbOBqW9MynttgVwHvXYkkNI=
---

# ZeroTier Browser - 内置 ZeroTier 的安卓浏览器

## 核心原理

不使用 Android 系统 VPN 通道，通过 **ZeroTier SDK (libzt)** 在应用进程内直接运行 ZeroTier 协议栈。只有本浏览器的网络请求能感知 ZeroTier 网络，其他 App 完全不受影响。

```
┌──────────────────────────────┐
│  系统其他 App（微信/TikTok等） │
│  → 正常走系统网络，不受影响     │
└──────────────────────────────┘
┌──────────────────────────────┐
│  ZT Browser（本应用）         │
│  ┌────────────────────────┐  │
│  │ WebView → SOCKS5代理   │  │
│  │          ↓              │  │
│  │     智能路由判断         │  │
│  │    ↙         ↘         │  │
│  │ ZT子网地址   公网地址    │  │
│  │   ↓           ↓        │  │
│  │ libzt       直连        │  │
│  └────────────────────────┘  │
└──────────────────────────────┘
```

## 编译步骤

### 1. 准备 libzt（ZeroTier SDK）

libzt 是 ZeroTier 官方提供的嵌入式 SDK，需要自行编译为 Android .aar 文件。

```bash
# 克隆 libzt 仓库
git clone https://github.com/zerotier/libzt.git
cd libzt

# 安装 Android NDK（需要 r25+）
# 设置环境变量
export ANDROID_NDK_HOME=/path/to/android-ndk

# 编译 Android 版本
make android

# 生成的产物在 android/ 目录下
# 将 libzt.aar 复制到项目的 app/libs/ 目录
cp android/libzt.aar app/libs/
```

**替代方案**：从已有 ZeroTier Android 客户端 APK 中提取 libzt.so，手动创建 .aar 包。

### 2. 准备 planet 文件（可选）

如果需要使用自定义根服务器：
```bash
# 将 planet 文件放到 app/src/main/assets/planet
cp /var/lib/zerotier-one/planet app/src/main/assets/
```

使用默认 ZeroTier 根服务器可跳过此步。

### 3. 编译 APK

```bash
# 设置 Android SDK 路径
export ANDROID_HOME=/path/to/android-sdk

# 编译
./gradlew assembleDebug

# APK 在 app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

### 首次启动
1. 打开 ZT Browser，自动弹出配置对话框
2. 输入 ZeroTier Network ID（16位十六进制，在 my.zerotier.com 创建网络获取）
3. 输入 ZT 子网（如 `10.147.0.0/16`），只有这些网段的请求走 ZeroTier
4. 点击"保存并连接"
5. 在 ZeroTier 管理后台授权本设备

### 日常使用
- 顶部状态栏显示 ZT 连接状态：● 在线 / ○ 离线
- 输入 ZT 内网 IP 或域名，浏览器自动走 ZeroTier
- 输入公网地址（如 baidu.com），浏览器正常直连
- 点击状态栏可重新配置

## 项目结构

```
zerotier-browser/
├── app/
│   ├── build.gradle.kts          # 应用依赖配置
│   ├── libs/
│   │   └── libzt.aar             # ZeroTier SDK（需自行编译）
│   └── src/main/
│       ├── AndroidManifest.xml   # 无 VPN 权限声明
│       ├── java/com/example/ztbrowser/
│       │   ├── MainActivity.kt        # 浏览器主界面
│       │   ├── ZeroTierService.kt     # libzt 封装，ZeroTier 协议栈管理
│       │   ├── ZTProxyServer.kt       # 本地 SOCKS5 代理服务器
│       │   ├── ZTWebViewClient.kt     # WebView 请求拦截与代理路由
│       │   ├── RequestBodyCollector.kt # POST body JS Bridge 收集器
│       │   └── ZTConfigDialog.kt      # 配置对话框
│       ├── res/
│       │   ├── layout/activity_main.xml
│       │   ├── drawable/url_bar_bg.xml
│       │   ├── values/themes.xml
│       │   ├── values/strings.xml
│       │   └── xml/network_security_config.xml
│       └── assets/
│           ├── planet             # 可选的 ZeroTier 根服务器配置
│           └── post_body_hook.js  # POST body 劫持注入脚本
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 关键技术细节

### 为什么不需要 VPN 权限？

| 方案 | 原理 | 影响范围 |
|------|------|----------|
| 标准 ZeroTier App | 使用 Android VpnService 创建 TUN 设备 | 全局，接管所有 App 流量 |
| **本应用方案** | libzt 在应用进程内运行，创建用户态协议栈 | **仅本应用**，不影响其他 App |

### 路由策略

- **ZeroTier 子网内的目标**（如 `10.147.x.x`）→ 走 ZeroTier 网络
- **公网目标**（如 `baidu.com`）→ 走系统网络直连
- **`.zt` 结尾的域名** → 强制走 ZeroTier（可自定义）

### 安全性

- ZeroTier 使用端到端 AES-256 加密
- 内网自签名证书：仅在 ZT 子网内允许绕过 SSL 验证
- 公网请求 SSL 验证保持完整

## 已知限制

1. **libzt 编译**：需要 NDK 编译环境，对新手有一定门槛
2. **性能**：应用层协议栈性能略低于内核态 VPN，但浏览网页足够
3. **后台保持**：Android 可能会杀死后台进程，导致 ZeroTier 连接中断
4. **P2P 打洞**：复杂 NAT 环境下可能需要 Moon 节点辅助
5. **POST body 大小**：JS Bridge 传递的请求体上限 5MB，超大会被丢弃
6. **FormData/Blob**：JS 劫持无法同步序列化 FormData 和 Blob body，这两类 POST body 会丢失
7. **单网络**：当前仅支持加入一个 ZeroTier 网络
8. **debug 构建**：CI 默认构建 debug APK，未做混淆/优化
*（内容由AI生成，仅供参考）*
