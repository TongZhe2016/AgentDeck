# Mac 与 Ubuntu 服务部署

日期：2026-10-01。

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
