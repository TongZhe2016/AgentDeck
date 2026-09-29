# 掌舵 · AgentDeck

Android 上的 SSH Agent 工作台：连接自己的电脑，操作 Codex、Claude Code，阅读已有会话，并查看项目的 Git Changes 与 Graph。

当前处于仓库规划和真机环境准备阶段。应用工程、电脑服务和构建命令将在底座验证后加入。

## 从这里开始

- [产品与技术方案](SSH_Agent_Android_Technical_Plan%281%29.md)：在原始文件中持续更新，包含产品范围、候选技术与验收场景；第 5.4 节定义 SSH 公钥／私钥登录。
- [仓库结构与管理设计](docs/repository-plan.md)：目录、模块边界、Git、协作与发布安排。
- [实施路线](docs/roadmap.md)：近期任务和阶段退出条件。
- [Wi-Fi ADB 调试](docs/development/wireless-adb.md)：手机配对、连接、验证和重连。
- [贡献指南](CONTRIBUTING.md)：改动、验证和提交方式。
- [Agent 工作约定](AGENTS.md)：自动化协作者的项目范围。

首个技术闭环选择一台实际 Android 手机、一台 Linux 主机和 Codex；完整首版再补齐多主机、Claude、图片、语音与通知。G2 先验证通信条件，后续实现眼镜交互。

## 许可证与上游复用

项目许可证待仓库所有者决定。引入候选项目代码前，记录来源、采用的 commit、许可证与需保留的声明。确定后加入 `LICENSE`；首次实际复用第三方代码时加入相应归属说明。
