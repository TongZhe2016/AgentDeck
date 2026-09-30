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

真实 App 对话补测期间 USB 断开，等待手机重新连接后补齐。Ubuntu 部署结果继续记录在本文。原始日志与设备元数据留在忽略的 `.local/server-deployment/`。
