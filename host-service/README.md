# AgentDeck 电脑端服务

要求 Node.js 24+、Git、已配置认证的 Codex CLI。当前实际验证 macOS 与 Codex CLI 0.155.1；电脑端沿用 Codex 的 API provider、模型和凭据配置。

```sh
cd host-service
npm ci
npm run build
npm start
```

服务默认监听 `127.0.0.1:4317`，数据保存在 `~/.agentdeck`。首启生成 `~/.agentdeck/token`，将其填到手机主机设置中的服务令牌字段。token 不显示在服务日志中。手机经 SSH 将本地端口转发到服务；不应把此端口暴露到局域网或公网。

环境变量：`AGENTDECK_DATA_DIR` 指定数据目录，`AGENTDECK_PORT` 指定端口，`AGENTDECK_CODEX` 指定 Codex 可执行文件。更换 token 文件并重启服务可撤销原有应用认证。每个数据目录运行一个服务实例。

手机断线不会关闭服务或其 Codex 子进程。直接 `npm start` 的生命周期依赖启动它的终端；日常使用应由系统用户服务管理。服务被关闭时正在执行的任务可能中断，重启标记为待核实，不自动重放。

`npm test` 在临时目录验证 Git、请求去重、审批失效、事件重放及服务重启；`npm run typecheck` 检查 TypeScript。真实模型测试会使用电脑上的已有 API 配置，开发验证记录见 `docs/validation/`。
