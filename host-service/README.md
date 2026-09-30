# AgentDeck 电脑端服务

要求 Node.js 24+、Git 2.25+、已配置认证的 Codex CLI（以 0.155.1 或更新版本为基准）。已实测 macOS 与 Ubuntu 20.04 x86_64；电脑端沿用 Codex 的 API provider、模型和凭据配置。

Ubuntu 联调发现 Codex 0.143.0 的会话历史接口遗漏命令记录，请升级后使用。可以将独立版本安装到用户目录，并用 `AGENTDECK_CODEX` 指向它，无需替换其他项目正在使用的 CLI。已验证版本与测试范围见 [Ubuntu 验证记录](../docs/validation/ubuntu.md)。

```sh
cd host-service
npm ci
npm run build
npm start
```

服务默认监听 `127.0.0.1:4317`，数据保存在 `~/.agentdeck`。首启生成 `~/.agentdeck/token`，手机通过已认证的 SSH 自动读取并加密保存，连接或重连时重新获取，无需手动复制。SSH 账号须能读取服务数据目录；使用自定义 `AGENTDECK_DATA_DIR` 时，在手机主机的“高级服务设置”填写对应目录。token 不显示在服务日志中。手机经 SSH 将本地端口转发到服务；不应把此端口暴露到局域网或公网。

环境变量：`AGENTDECK_DATA_DIR` 指定数据目录，`AGENTDECK_PORT` 指定端口，`AGENTDECK_CODEX` 指定 Codex 可执行文件。更换 token 文件并重启服务会令旧令牌失效；仍有 SSH 权限的手机会在重连时自动获取新令牌。撤销手机访问时，应同时撤销其 SSH 密钥或密码权限。每个数据目录运行一个服务实例。

手机断线不会关闭服务或其 Codex 子进程。直接 `npm start` 的生命周期依赖启动它的终端；日常使用应由系统用户服务管理。服务被关闭时正在执行的任务可能中断，重启标记为待核实，不自动重放。

`npm test` 在临时目录验证 Git、请求去重、审批失效、事件重放及服务重启；`npm run typecheck` 检查 TypeScript。真实模型测试会使用电脑上的已有 API 配置，开发验证记录见 `docs/validation/`。

## macOS 常驻运行

从仓库根目录运行 `node scripts/install-macos-service.mjs` 查看版本、路径与启动方式；加入 `--install` 才会安装和启动。安装器构建服务并复制到 `~/.local/share/agentdeck/0.1.0/`，注册 `~/Library/LaunchAgents/com.worldcopy.agentdeck.plist`，由当前用户登录后的 launchd 管理。先停止占用 4317 的开发服务。

升级前让正在执行的任务结束，再重新运行安装器。配置和数据保留在 `~/.agentdeck`。停止服务：

```sh
launchctl bootout "gui/$(id -u)/com.worldcopy.agentdeck"
```

删除 LaunchAgents 中的对应 plist 可关闭后续自动启动；保留数据目录可供重新安装使用。日志为 `~/.agentdeck/service.log` 和 `service-error.log`。本次开发已验证安装器预览；未在用户登录项中自动安装。

## Ubuntu 常驻运行

需要 Node.js 24+、Git 2.25+、已配置认证的 Codex CLI，以及可用的 `systemctl --user`。系统 Node 较旧时，可以把 Node.js 24 官方 Linux 发行包解压到用户目录，再把其 `bin` 加到当前 `PATH`；安装器记录当前 Node 绝对路径，不改变系统 Node。该运行时目录需持续保留。

从仓库根目录运行：

```sh
node scripts/install-linux-service.mjs           # 查看安装路径
node scripts/install-linux-service.mjs --install # 构建、安装并启动
loginctl enable-linger                          # 让服务在退出 SSH 后继续运行
systemctl --user status agentdeck.service
```

`enable-linger` 在部分服务器需要管理员授权；没有启用时，不能保证最后一个登录会话退出后的运行。Codex 不在当前 PATH 时，用 `AGENTDECK_CODEX=/绝对路径/codex` 指定。安装器会保留当前 PATH，以便服务能找到 Node、Git 与项目工具。手机使用服务器可达的 SSH 地址和端口，无需开放服务的 4317 端口。

程序安装于 `~/.local/share/agentdeck/0.1.0/`，用户 unit 为 `~/.config/systemd/user/agentdeck.service`，数据与令牌位于 `~/.agentdeck/`。日志使用 `journalctl --user -u agentdeck.service`。升级前等待任务结束，再重跑安装器；停止并取消自启使用 `systemctl --user disable --now agentdeck.service`。保留数据目录可重新安装。

## 本地语音转写

`node scripts/setup-transcription.mjs` 显示安装内容；`--install` 创建独立 Python 环境，安装 faster-whisper 1.2.1 并下载多语言 base 模型。需要 Python 3.9+，可用 `AGENTDECK_PYTHON` 指定解释器。

安装后重跑对应系统的服务安装器，使用户服务带上转写环境。前台开发设置 `AGENTDECK_TRANSCRIBE_PYTHON`（虚拟环境中的 Python 绝对路径）和 `AGENTDECK_WHISPER_MODEL`（模型目录）。首轮联调的依赖和模型安装在仓库 `.local/`，没有改变系统 Python 或用户登录服务。

手机录音经 SSH 上传，在电脑 CPU 上转写；运行时不下载模型，也不把音频交给模型 API。转写最长等待 120 秒；失败可重试，成功文字进入手机草稿。准确率取决于语言、声音和模型，发送前可以编辑。临时录音转写后删除，已发送图片保留供原生会话恢复。

## 日常使用边界

电脑服务管理的会话允许手机观察和控制；其他终端启动的历史默认只读，用户确认原会话停止后才能恢复。本版没有终端重新附着入口。正文搜索每次最多扫描十页原生历史，继续搜索使用游标，读取失败会显示错误。

Android 缓存最近读取的会话摘要、每个会话最后 500 项显示内容、最近项目的 Git 状态与 Graph，以及最近一个 Diff／提交详情。未缓存内容需要重新连接。清理阅读缓存保留凭据和草稿。Diff 上限 512 KiB，命令显示最后 16,000 字符，超限会标示截断；完整执行记录仍由 Codex 保留。

后台同步使用用户主动开启的 dataSync 前台服务。Android 15+ 对此类型有六小时／24 小时后台额度；达到系统时限会断开手机连接，电脑任务继续。强制停止 App 后不会继续通知，再次打开并连接可读取结果。实际机型的省电策略仍需真机验证。

来源：[faster-whisper](https://github.com/SYSTRAN/faster-whisper)、[Android 前台服务时限](https://developer.android.com/develop/background-work/services/fgs/timeout)。
