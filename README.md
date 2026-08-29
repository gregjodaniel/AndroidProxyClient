# AndroidProxyClient 📱🚀

基于 **SingBox 核心 / Android VpnService** 的开源高性能 Android 代理客户端。

[![Build and Release Android Proxy APK](https://github.com/gregjodaniel/AndroidProxyClient/actions/workflows/build.yml/badge.svg)](https://github.com/gregjodaniel/AndroidProxyClient/actions/workflows/build.yml)
[![GitHub release](https://img.shields.io/github/v/release/gregjodaniel/AndroidProxyClient?color=blue)](https://github.com/gregjodaniel/AndroidProxyClient/releases)

---

## 🌟 核心特性

- 🌐 **全协议支持**：
  - **VLESS** (支持 Reality / TLS, TCP / WebSocket / gRPC / HTTPUpgrade)
  - **VMess** (支持 WebSocket / TCP / gRPC / TLS)
  - **Hysteria2 (Hy2)** (支持端口跳跃与混淆)
  - **TUIC** (支持 BBR 拥塞控制与原生 UDP)
  - **Trojan**
  - **Shadowsocks**
- ⚡ **并发延迟测速**：一键对所有节点进行低延迟并发握手测试。
- 📋 **订阅与单节点导入**：支持标准 Base64 订阅文本、HTTP/HTTPS 在线订阅以及各种协议 URI。
- 🛡️ **智能规则分流**：
  - 绕过中国大陆与局域网 (GeoIP / Geosite 分流)
  - 全局代理
  - 全局直连
- 📊 **实时流量监控**：实时上下行网速计算与累计上传/下载流量统计。
- 🎨 **Material 3 设计**：现代简洁的 UI，支持暗色主题自适应与前台状态栏控制。

---

## 📦 下载与安装

直接前往 [Releases 页面](https://github.com/gregjodaniel/AndroidProxyClient/releases) 下载最新的 **`AndroidProxyClient-v1.0.0.apk`** 安装即可。

---

## 🛠️ 本地构建

```bash
# 1. 克隆仓库
git clone https://github.com/gregjodaniel/AndroidProxyClient.git
cd AndroidProxyClient

# 2. 编译 APK
gradle assembleRelease
```

---

## 📄 开源许可证

MIT License
