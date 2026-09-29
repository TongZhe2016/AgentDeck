# 掌舵 · AgentDeck

Android 上的 SSH Agent 工作台：连接自己的电脑，操作 Codex，阅读已有会话，并查看项目的 Git Changes 与 Graph。Claude Code 和 G2 随后接入。

当前已实现 Codex 首版工作台：多主机、SSH 密码／手机生成 Ed25519 密钥登录、加密凭据、原生历史与正文搜索、新建／恢复会话、流式回复、审批／取消、断线事件恢复、后台通知、图片／分享和电脑端语音转文字。Git 支持 Changes、Diff、真实提交 DAG、分页和合并父提交选择。

Android 工程位于根目录，应用模块为 `app/`，application ID 为 `com.worldcopy.agentdeck`。在 Android Studio 中打开根目录，或用 `./gradlew :app:assembleDebug` 构建。电脑服务位于 `host-service/`，仅监听回环地址，经 SSH 隧道访问。

模拟器到本机 Mac 的 SSH、真实 Codex API 文字／图片、审批／取消、断线恢复和后台通知已实测；详情见 [验证记录](docs/validation/codex-workbench.md)。开发配置以 Codex CLI 0.155.1、Node.js 24+ 为基准，手机不保存模型 API key。

## 启动使用

1. 电脑启用 SSH，安装并配置 Codex CLI。电脑端运行 `cd host-service && npm ci && npm run build && npm start`。
2. 手机“密钥”页创建 Ed25519 密钥，将公钥配置到目标账号 `~/.ssh/authorized_keys`；也可以选择密码登录。
3. 添加主机，填 SSH 地址、账号、认证方式及电脑 `~/.agentdeck/token` 的服务令牌，服务端口默认 `4317`。模拟器连接本机 Mac 使用 `10.0.2.2`；真机使用电脑可达地址。
4. 核对 SSH 主机身份，连接后打开工作台，输入电脑上的项目绝对路径，新建或恢复 Codex 会话。
5. 需要后台接收时开启“后台同步”并允许通知。电脑端服务应常驻；macOS 安装与语音设置见 [电脑服务指南](host-service/README.md)。

图片会缩放并规范方向后上传。录音最长两分钟，在电脑本地转写，回到手机草稿供确认，随后手动发送。文字／图片分享入口也先进入选定会话的草稿。

## 从这里开始

- [产品与技术方案](SSH_Agent_Android_Technical_Plan%281%29.md)：在原始文件中持续更新，包含产品范围、候选技术与验收场景；第 5.4 节定义 SSH 公钥／私钥登录。
- [仓库结构与管理设计](docs/repository-plan.md)：目录、模块边界、Git、协作与发布安排。
- [实施路线](docs/roadmap.md)：近期任务和阶段退出条件。
- [Wi-Fi ADB 调试](docs/development/wireless-adb.md)：手机配对、连接、验证和重连。
- [贡献指南](CONTRIBUTING.md)：改动、验证和提交方式。
- [Agent 工作约定](AGENTS.md)：自动化协作者的项目范围。

当前验证目标为 Android 模拟器和这台 Mac。物理手机后台、切网、功耗及其他电脑系统留待对应设备验收。debug APK 可从本地 `app/build/outputs/apk/debug/app-debug.apk` 或 GitHub Actions Android 构建产物取得。

## 许可证与上游复用

项目许可证待仓库所有者决定。引入候选项目代码前，记录来源、采用的 commit、许可证与需保留的声明。确定后加入 `LICENSE`；首次实际复用第三方代码时加入相应归属说明。
