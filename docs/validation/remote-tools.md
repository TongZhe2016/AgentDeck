# SSH 工具与手机安装

验证日期：2026-10-16。Android API 36 ARM64 模拟器、真实 macOS SSH 服务，以及 Ubuntu 20.04 x86_64 的隔离用户目录。没有更新或覆盖日常运行的 AgentDeck 服务。

## 实现

- 项目页长按主机的“SSH 终端、文件与安装”使用独立 SSHJ 连接，复用已保存的密码／密钥与主机公钥确认，不需要电脑服务、端口转发或服务令牌。
- 终端通过 SSH PTY 提供 `xterm-256color` Shell，内置 xterm.js 6.0.0／fit 0.11.0。直接处理 UTF-8 字节，页面变大／变小发送窗口尺寸；Ctrl-C、Esc、Tab、方向键和 Enter 可从辅助按键发送。绘制端完成每块解析后才继续推送，避免持续输出塞满 WebView；最近 1 MiB 原始输出只在内存保留，用于切页和 Activity 重建时重新附加。离开工具或明确重连会关闭原 Shell，电脑端普通 Shell 命令可能随 SSH 结束；需要跨连接保留任务时可使用电脑自己的 tmux。
- SFTP 展示目录、文件大小、修改时间，支持绝对／相对路径、`~`、上级、主目录和刷新。跟随可访问的目录链接；失效链接和特殊文件不提供下载。下载用 64 KiB 缓冲流写 Android 文件选择器返回的 URI，显示字节进度及完成／取消／失败状态。中断后保留的目标文件可能不完整，没有自动重试或断点续传。
- Gradle 的生成资源任务把当前电脑服务的已编译代码、安装器和终端许可打入 APK。服务包约 44 KiB，终端主 JS 约 512 KiB；不含 Node/Codex 二进制。macOS tar 的文件扩展属性不写入跨平台安装包。
- 手机安装通过 SFTP 上传临时目录，然后执行包内 bootstrap。支持 macOS／systemd Linux 的 x64 和 ARM64，缺少 Node 24+ 或 Codex CLI 时联网装入 SSH 用户目录，不使用 sudo。注册用户服务时记录实际 Node、Codex、数据目录和端口，通过令牌认证 `/v1/health` 后才显示成功。正常结束（包括失败）清理上传目录；连接中断可能留下 `.agentdeck-install-*` 临时目录，可在终端检查并移除。
- 首次安装入口会拒绝已有服务配置或已安装的电脑服务；已有安装仍使用原仓库安装器维护。macOS 需 SSH 账号已登录桌面，Linux 需可用 systemd 用户管理器；无人登录时持续运行需用户启用 Linger。安装器会检查端口占用，并输出 Codex 登录前的 PATH 命令。账号认证、自定义 Catalog/provider、语音转写依赖仍由电脑端配置。

## 验证结果

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug` 通过，lint 无错误。
- `node --test scripts/mobile-install.test.mjs`：macOS 与 Ubuntu 各三项通过，覆盖 systemd 路径与百分号、launchd XML 转义、自定义端口／目录，以及隔离目录中启动真实打包 HTTP 服务、令牌健康请求、拒绝二次安装。服务管理器为测试替身，没有注册到当前账号。
- Linux 的立即退出 Codex 测试暴露并修复未处理 stdin EPIPE 会令整个电脑服务退出的问题：拒绝未完成 RPC，释放当前子进程，只处理该子进程的一次停止事件。电脑服务 24 项行为测试通过；补充子进程清理后，生命周期专项和两平台打包安装测试通过。
- `RemoteToolsTest` 三项真实 SSH 测试通过：PTY 中文与 31×92 尺寸、合并 stderr 与退出码 7、128 KiB 二进制往返、上传 APK 内安装包并远端解压；界面终端执行后切页重新附加、SFTP 链接／失效链接、下载逐字节比对；手机上传并执行时对已有安装报告失败且清理临时上传目录。
- 大字体 2.0、深色横屏的界面流程通过，含 SFTP 列表滚动、终端重新附加、安装确认／取消和 SSH 明确重连后继续执行。截图发现并修复 WebView 文字缩放导致字符重叠：固定 WebView text zoom，再按系统 font scale 设置 xterm 单元尺寸。
- Ubuntu 从隔离 PATH／HOME 启动 bootstrap，确实下载 Node 24.22.0 与 Codex CLI 0.162.1 到临时用户目录，安装打包服务并通过令牌健康检查。systemd 命令使用启动真实临时 Node 服务的替身，未注册真实 unit；测试后关闭服务并清理运行时、认证空目录、安装文件和上传材料。未发起模型请求。
- 模拟器临时 SSH 公钥授权已撤销，Android 测试主机和身份已删除。日志和截图仅保留在忽略的 `.local/remote-tools/`。

## 尚未实测

新电脑上真实 launchd／systemd 的完整首次注册、ARM64 Linux／Intel Mac、物理手机软键盘与后台网络切换仍待设备验收。已有原生用户服务安装器的实测记录见 [服务部署](server-deployment.md)，不能代替手机新安装器的系统服务管理器验收。本轮没有安装 macOS 菜单栏 App，也没有配置真实 Codex 认证；手机安装的是常驻电脑服务。
