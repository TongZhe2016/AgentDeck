# Mac 与 Ubuntu 服务部署

首次部署：2026-10-01。最近更新：2026-10-08。

## 本机 Mac

- SwiftUI／AppKit 菜单栏 App 已构建、ad-hoc 签名并安装到 `~/Applications/AgentDeck Server.app`。
- 后台 Node 服务与菜单栏分别注册为用户 LaunchAgent，均已启动；服务只监听 `127.0.0.1:4317`。
- 实际重装验证通过。修复旧服务卸载未完成时立即 bootstrap 返回错误 5 的问题。
- 控制台实际点击停止、确认、启动，界面状态与 health 均正确。关闭／退出菜单栏与 Node 服务各自独立。
- 电脑服务的 8 项行为测试通过，Mac 上已配置的 Codex CLI 为 0.159.2。
- vivo 真机使用已有 App 密钥，经已有 SSH 入口连接此 Mac、自动读取服务令牌、请求 darwin health 并列出 Codex 原生会话，`CredentialIntegrationTest` 通过。
- 修正真机“macbook”条目的用户名为本机用户。沿用已有 SSH 公钥授权，没有导出手机私钥。

- Android 模拟器使用新安装的 Mac 常驻服务，从真实 App 界面连接 SSH、新建隔离项目的会话、发送消息并收到 `AGENTDECK_UI_OK`，`WorkbenchSmokeTest#codexChatThroughSsh` 通过。沿用本机 API 认证。
- 模拟器测试密钥由 Android 创建；结束后删除 Android 测试身份与主机，并撤销 Mac 上的临时公钥授权。

vivo 的真实界面对话补测期间 USB 断开，因此该项由模拟器完成；真机到 Mac 的 SSH、自动令牌与会话读取已通过，真机完整对话仍待 USB 恢复后验证。

## 五台 Ubuntu

通过本机已有 SSH 别名逐台安装 AgentDeck 0.1.0。TZ4090 与 eez75 首次安装；eez76、eez144、eez145 更新已有用户服务，更新前确认没有运行中或等待审批的任务。

| SSH 别名 | 实际主机 | Node | 服务使用的 Codex | 用户服务／自启／Linger |
| --- | --- | --- | --- | --- |
| tz-4090 | tz-Ubuntu | 24.21.0 | 0.159.2 | active／enabled／yes |
| eez75 | eez075 | 24.21.0 | 0.159.2 | active／enabled／yes |
| eez76 | eez076 | 24.21.0 | 0.159.2 | active／enabled／yes |
| eez144 | eez144 | 24.21.0 | 0.157.1 | active／enabled／yes |
| eez145 | eez145 | 24.21.0 | 0.159.2 | active／enabled／yes |

- Node 使用永久用户目录 `~/.local/share/agentdeck/node-v24.21.0-linux-x64`，安装器记录绝对路径。eez75 的 Codex 安装在独立用户目录；其他主机沿用已可用的版本与认证。
- 五台均完成服务构建与 8 项行为测试、Linux health／协议版本检查和 Codex 原生会话读取。安装 SSH 退出后，重新连接检查五台仍为 active、enabled、Linger=yes，只监听 `127.0.0.1:4317`。
- Android 模拟器经五个实际 SSH 入口分别完成密钥认证、SSH 读取令牌、端口转发、health 与 Codex 原生会话读取，五次 `CredentialIntegrationTest` 全部通过。本次没有在 Ubuntu 发起真实模型任务。
- 联调结束后已撤销五台临时公钥授权，并删除 Android 测试身份。eez145 首次撤销因 SSH 连接重置失败，重新 SSH 后已成功删除该授权。

各主机程序位于 `~/.local/share/agentdeck/0.1.0/`，unit 位于 `~/.config/systemd/user/agentdeck.service`，数据位于 `~/.agentdeck/`。日志用 `journalctl --user -u agentdeck.service`；启动用 `systemctl --user start agentdeck.service`，停止用 `systemctl --user stop agentdeck.service`。

原始日志、临时部署脚本与设备元数据留在忽略的 `.local/server-deployment/`。没有提交凭据或私钥。

## 2026-10-08：六台服务同步更新

手机的执行设置报“接口不存在”：六台运行的旧服务均未提供 `GET /v1/models`。本次将 MacBook、EEZ076、EEZ145、EEZ075、EEZ144、TZ4090 更新到仓库 `d16f46c` 对应的电脑服务构建，包含模型／思考强度目录、会话执行设置与会话摘要分页。应用版本仍为 0.1.0，协议版本仍为 1。

按用户授权，将六台 AgentDeck 服务使用的 Codex 统一更新为本次 npm 查询的正式版本 0.161.0，安装在独立的 `~/.local/share/agentdeck/codex-0.161.0/`，并更新各服务的 `AGENTDECK_CODEX`。沿用各电脑已有的 Codex 认证与配置。

| 主机 | 服务使用的 Codex | 模型目录条目 | 会话摘要首批 | 服务状态 |
| --- | --- | --- | --- | --- |
| MacBook | 0.161.0 | 8 | 40 | launchd 已启动，health 200 |
| EEZ076 | 0.161.0 | 8 | 40 | active／enabled／Linger=yes |
| EEZ145 | 0.161.0 | 8 | 21 | active／enabled／Linger=yes |
| EEZ075 | 0.161.0 | 8 | 40 | active／enabled／Linger=yes |
| EEZ144 | 0.161.0 | 8 | 10 | active／enabled／Linger=yes |
| TZ4090 | 0.161.0 | 11 | 18 | active／enabled／Linger=yes |

- 部署前电脑服务构建与 10 项行为测试通过。逐台停止前确认没有运行中任务或待处理批准，备份旧程序、服务配置与 SQLite 数据库；更新后检查 health、模型目录、会话摘要与执行设置路由。
- 六台模型目录均包含 `gpt-6.1-sol`、`gpt-6-astra` 及各自的思考强度。目录以每台电脑的实际配置和返回结果为准。
- 执行设置路由使用不存在的测试会话 ID 调用，均返回预期的 400「请先恢复此历史会话」，确认路由已注册并进入会话校验。这项部署检查没有修改真实会话设置，也没有发起真实模型推理。
- TZ4090 原有 Codex 0.160.0 的 `model/list` 返回空目录，包含隐藏模型也为空；首次更新验证未通过后恢复了旧服务。单独验证 0.161.0 返回完整目录后，再切换服务并完成更新。EEZ144 原有 0.157.1 未列出 6.1 Sol，更新后已列出。
- EEZ075 一次 SSH 建连超时，重新连接后部署成功。手机暂未连接，本次未安装手机 APK；手机重新打开执行设置或点击「重试读取模型」可刷新目录。

本次部署日志与辅助脚本保留在忽略的 `.local/service-update-20261008/`。各电脑备份保留在 `~/.local/share/agentdeck/backup-before-20261008-*/`。


## 2026-10-08：文件下载接口

MacBook、EEZ076、EEZ145、EEZ075、EEZ144、TZ4090 均已部署 `GET /v1/files?path=&cwd=`。更新前确认没有活动执行与待处理请求，保留旧程序备份，沿用现有 Codex 0.161.0、认证及服务配置。

六台服务更新后的 health 均正常；每台创建隔离的 64 KiB 二进制文件，通过带认证的下载接口按相对路径下载，逐字节比对通过后删除测试文件。接口按 SSH 账号现有文件权限读取，流式返回，不需要启动或恢复 Codex 会话。部署日志及辅助脚本位于 `.local/markdown-download/`；旧程序保留在各主机 `~/.local/share/agentdeck/backup-markdown-download-*`。

## 2026-10-09：后台会话目录

六台服务均已更新为独立维护的持久摘要目录：后台约 2 秒发现新近会话、每分钟完整校准，手机读取缓存并接收目录增量。部署前确认无活动执行与待处理请求，保留旧程序备份；各台目录初始化、health 与会话分页接口均通过。目录计数和实测结果见 [常驻会话目录验证](session-catalog.md)。
