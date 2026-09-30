# 掌舵 · AgentDeck

Android 上的 SSH Agent 工作台：连接自己的电脑，操作 Codex，阅读已有会话，并查看项目的 Git Changes 与 Graph。Claude Code 和 G2 随后接入。

当前已实现 Codex 首版工作台：跨主机项目首页、多主机、SSH 密码／生成或导入密钥登录、加密凭据、原生历史与正文搜索、新建／恢复会话、流式回复、审批／取消、断线事件恢复、后台通知、图片／分享和电脑端语音转文字。Git 支持 Changes、Diff、真实提交 DAG、分页和合并父提交选择。Graph 使用紧凑单行标题与相对时间，长标题省略，点击行内展开完整说明、引用和文件变更。

Android 工程位于根目录，应用模块为 `app/`，application ID 为 `com.worldcopy.agentdeck`。在 Android Studio 中打开根目录，或用 `./gradlew :app:assembleDebug` 构建。电脑服务位于 `host-service/`，仅监听回环地址，经 SSH 隧道访问。

模拟器到本机 Mac 的 SSH、真实 Codex API 文字／图片、审批／取消、断线恢复和后台通知已实测；详情见 [验证记录](docs/validation/codex-workbench.md)。开发配置以 Codex CLI 0.155.1、Node.js 24+ 为基准，手机不保存模型 API key。

## 启动使用

1. 电脑启用 SSH，安装并配置 Codex CLI。电脑端运行 `cd host-service && npm ci && npm run build && npm start`。
2. 手机“密钥”页创建 Ed25519 密钥，将公钥配置到目标账号 `~/.ssh/authorized_keys`；已有电脑密钥可点“导入已有密钥”，选择文件或粘贴私钥，支持 OpenSSH／PEM 的 Ed25519、RSA 和 ECDSA。公钥可自动提取，或同时选择 `.pub` 核对；加密私钥需在导入时输入口令。也可以选择密码登录。
3. 添加主机，填写 SSH 地址、账号和认证方式。App 通过已认证的 SSH 自动读取电脑 `~/.agentdeck/token`，无需手动填写服务令牌；自定义服务目录和端口在“高级服务设置”中配置，端口默认 `4317`。模拟器连接本机 Mac 使用 `10.0.2.2`；真机使用电脑可达地址。
   创建相似主机时，可在已有主机的三个点菜单选择“克隆”，修改名称、地址、端口、账号和认证方式后保存。密钥登录沿用所选密钥；密码登录留空沿用原密码，也可填写新密码。新主机首次连接会重新确认 SSH 主机身份并自动获取服务令牌。
4. 默认进入“项目”首页，点击“同步项目”读取各主机的已有会话。项目按主机与文件夹完整路径区分，突出显示文件夹名、主机名和对话数；默认折叠，点击展开后选择对话。离线时展示已缓存项目。
5. 在项目中打开或新建会话；首次使用一个还没有对话的文件夹，可从“主机”工作台输入绝对路径创建。主机连接设置仍在“主机”，身份管理在“密钥”。
6. 需要后台接收时开启“后台同步”并允许通知。电脑端服务应常驻；macOS／Ubuntu 常驻安装与语音设置见 [电脑服务指南](host-service/README.md)。

图片会缩放并规范方向后上传。录音最长两分钟，在电脑本地转写，回到手机草稿供确认，随后手动发送。文字／图片分享入口也先进入选定会话的草稿。

## 从这里开始

- [产品与技术方案](SSH_Agent_Android_Technical_Plan%281%29.md)：在原始文件中持续更新，包含产品范围、候选技术与验收场景；第 5.4 节定义 SSH 公钥／私钥登录。
- [仓库结构与管理设计](docs/repository-plan.md)：目录、模块边界、Git、协作与发布安排。
- [实施路线](docs/roadmap.md)：近期任务和阶段退出条件。
- [Android 界面设计](docs/design/android-ui.md)：品牌主题、页面层级与手机／宽屏布局。
- [Mac 菜单栏 App](macos/README.md)：本机后台服务、连接信息与日志控制台。
- [Wi-Fi ADB 调试](docs/development/wireless-adb.md)：手机配对、连接、验证和重连。
- [贡献指南](CONTRIBUTING.md)：改动、验证和提交方式。
- [Agent 工作约定](AGENTS.md)：自动化协作者的项目范围。

当前验证目标为 Android 模拟器、macOS 和 Ubuntu 20.04 x86_64（见 [Ubuntu 验证记录](docs/validation/ubuntu.md)）。物理手机后台、切网、功耗及 Windows 留待对应设备验收。debug APK 可从本地 `app/build/outputs/apk/debug/app-debug.apk` 或 GitHub Actions Android 构建产物取得。

## 许可证与上游复用

项目许可证待仓库所有者决定。引入候选项目代码前，记录来源、采用的 commit、许可证与需保留的声明。确定后加入 `LICENSE`；首次实际复用第三方代码时加入相应归属说明。
