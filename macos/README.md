# 掌舵电脑服务 · Mac App

菜单栏 App 使用 SwiftUI 和 AppKit，支持 macOS 13+。它负责后台服务状态、启动／停止、手机连接信息和日志入口；Codex、会话、Git 与任务执行仍由 `host-service/` 负责。

## 安装

电脑安装 Node.js 24+、Git、Codex CLI 并配置 Codex 认证，使用 Xcode Command Line Tools 提供的 Swift 编译器。从仓库根目录运行：

```sh
node scripts/install-macos-service.mjs --install
open "$HOME/Applications/AgentDeck Server.app"
```

只构建 App 时使用 `node scripts/build-macos-app.mjs`，产物位于 `.local/macos/AgentDeck Server.app`。本地安装使用 ad-hoc 签名；正式对外发行需另行签名和公证。

## 设计

- 控制台显示服务状态、当前局域网 SSH 地址、SSH 端口与用户名，可复制连接信息。
- 用户在系统设置开启远程登录，手机选择密码或密钥登录；服务令牌自动经 SSH 获取。
- 开始／停止操作交给 launchd。停止前确认正在执行的任务可能中断，手机断线时电脑任务继续。
- 每五秒检查本机服务 health，读取本地令牌进行认证。界面和日志入口不显示模型 API key 或服务令牌。
- 品牌图标沿用 Android 的船舵与终端标识，布局、字体和控件使用 macOS 原生样式，并跟随系统外观。

## 生命周期

安装位置为 `~/Applications/AgentDeck Server.app`。菜单栏 App 与 Node 服务分别由 `com.worldcopy.agentdeck.manager`、`com.worldcopy.agentdeck` 两个用户 LaunchAgent 启动。

关闭控制台窗口会保留菜单栏；退出菜单栏 App 不停止电脑服务。两者随用户登录启动，菜单栏退出后在下次登录重新出现。此方式需要当前用户已登录，不是登录前的系统 daemon。

服务代码位于 `~/.local/share/agentdeck/0.1.0/`，数据、令牌与日志位于 `~/.agentdeck/`。升级前完成运行中的任务，再重跑安装器；构建完成后才替换安装，并等待旧 launchd 服务卸载完成。

停止并取消两者的登录启动，可在停止服务后删除 `~/Library/LaunchAgents/com.worldcopy.agentdeck{,.manager}.plist`。保留 `~/.agentdeck/` 可继续使用已有配置和记录。
