# Ubuntu 电脑端验证

日期：2026-09-30。Android API 36 ARM64 模拟器连接三台实际 Ubuntu 20.04.6 LTS x86_64 服务器。Git 均为系统自带的 2.25.1；AgentDeck 使用独立用户目录中的 Node.js 24.21.0，保留原有系统 Node 和 Codex 配置。

## 构建与安装

三台服务器分别执行 `npm ci && npm test`：8 项测试全部通过，涵盖 API 令牌、附件与请求去重、审批、断线后的执行归属、重启恢复、Git Changes／Graph、linked worktree 及历史搜索。

原代码在 Git 2.25 上使用了不支持的 `git init -b` 和 `--path-format=absolute`。已改用 `symbolic-ref` 设置测试分支，以及相对于仓库根目录解析 `--git-common-dir`；普通仓库和 linked worktree 均验证了公共目录与独立工作树目录。

三台都通过 `scripts/install-linux-service.mjs --install` 实际构建、安装并启动。`agentdeck.service` 为 active／enabled，`Linger=yes`，端口只监听 `127.0.0.1:4317`。实际重新登录 SSH 后检查服务仍在运行；没有重启整台服务器。安装器修正了 Ubuntu 20.04 systemd 对 WorkingDirectory 引号的解析差异，并支持重装已有服务。

## Android → SSH → Ubuntu → Codex

模拟器通过服务器对外的 SSH 端口直接连接，不借用 Mac 的 SSH 私钥。测试密钥由 Android 的现有 Ed25519 生成功能创建；Mac 只通过已有 SSH 连接部署该公钥，读取主机公钥与 AgentDeck 服务令牌。私钥留在手机加密存储中。

三个主机配置均实际保存到 App，再分别完成：

1. 使用手机私钥认证、建立本地端口转发并请求 Linux 服务 health。
2. 读取隔离测试项目的 Git 状态、公共目录和 Graph。
3. 新建 Codex 会话，请模型调用命令工具执行 `uname -s`。
4. 提交后立即关闭手机 SSH 隧道，等待 12 秒，再重新连接读取执行状态与历史。
5. 检验完成状态、模型回复；0.156.1／0.157.1 的命令历史确认退出码为 0、输出包含 Linux。

已验证 Codex 版本为 0.156.1 和 0.157.1，均沿用服务器已有 API 配置。另一个服务器原先使用 0.143.0：实际执行成功，但 `thread/turns/list` 返回的历史遗漏命令项；本次给 AgentDeck 单独安装 0.157.1 后通过全部断线／命令历史测试，保留原来的全局 CLI。因此不把 0.143.0 列为支持组合。

EEZ145 还通过 Compose 页面测试：在多主机列表选择目标主机、点击连接和工作台、填写 Ubuntu 项目路径、新建会话、发送消息并显示 `AGENTDECK_UI_OK`。Android debug APK、测试 APK 构建与 lint 均通过。

设备测试入口为 `CredentialIntegrationTest#sshIntegration`。显式传入 `sshPhase`（prepare／configure／connect／cleanup）、`sshFixture`、`sshName`、`sshHost`、`sshPort`、`sshUser`、`sshHostKey` 和 `serviceToken`；`connect` 增加 `sshProject` 时执行 Ubuntu Git／真实模型／断线测试。令牌只用于本地调用，不写进提交或示例。默认设备测试不会自动连接真实服务器。

## 安装保留与测试清理

三台保留运行中的 AgentDeck 用户服务，Node 固定在 `~/.local/share/agentdeck/node-v24.21.0-linux-x64/`；独立 Codex 0.157.1 位于相应服务器的 `~/.local/share/agentdeck/codex-0.157.1/`。移动运行时后重新安装服务，分别确认 health 和原生会话列表可用。

本轮测试结束已逐台撤销唯一注释标记的公钥授权，并通过 cleanup 删除模拟器的三个临时主机配置和测试私钥。Mac 原有 SSH 登录配置保留。再次在 App 日常连接时，创建正式手机密钥并授权，按 [电脑服务指南](../../host-service/README.md) 填入令牌。

## 范围

本轮验证 Ubuntu x86_64 的安装、SSH、Git 和 Codex。Ubuntu 语音依赖、ARM64 Linux、Windows、服务器重启及物理手机网络切换未在本轮验证。临时测试项目与原始诊断留在独立目录，源码只保留脱敏结论。
