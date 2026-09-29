# AgentDeck protocol 1

HTTP 与 SSE 仅经 SSH loopback 转发访问。每个请求必须携带 `Authorization: Bearer <电脑服务令牌>`。电脑服务监听 `127.0.0.1:4317`。错误响应为 `{ "error": "可显示的原因" }`。

- `GET /v1/health`：协议版本、服务版本和平台。
- `GET /v1/snapshot`：最近执行、待处理请求、当前事件游标。
- `GET /v1/events?after=<seq>`：SSE，每条 `data` 包含 `{seq,type,data,createdAt}`，严格按序补发。客户端使用最后成功处理的序号恢复；无效游标需重取快照。事件类型为 `run.updated`、`agent.event`、`approval.requested`、`approval.resolved`。
- `GET /v1/sessions?search=&cursor=`：Codex 原生会话标题搜索、分页。查询不恢复执行。
- `POST /v1/sessions {cwd}`：创建受管理 Codex 会话，采用 workspace-write 沙箱和 on-request 审批。
- `GET /v1/sessions/:id`：只读历史与 managed 标记。
- `POST /v1/sessions/:id/resume {confirmStopped:true}`：用户确认原有执行已停止后恢复原生 ID。
- `POST /v1/runs {clientRequestId,threadId,text}`：先保存请求身份，再派发。相同 ID 和内容返回已有结果；同 ID 不同内容报错；同会话活动执行互斥。
- `POST /v1/runs/:id/cancel`：取消具体 turn。
- `POST /v1/runs/:id/reconcile`：根据原生历史核实 unknown 执行，不重新发送。
- `POST /v1/approvals/:id {decision:"accept"|"decline"}`：只处理仍有效的具体请求。问题使用 `{answers:{questionId:{answers:["answer"]}}}`。
- `GET /v1/git/status?cwd=`：分支、HEAD 可达计数、浅克隆、上游方向、唯一路径计数及分组变更。
- `GET /v1/git/diff?cwd=&path=&group=`：staged／unstaged／untracked／conflict；历史差异增加 commit 和 parent（从 0 开始）。上限 512 KiB，返回 truncated。
- `GET /v1/git/graph?cwd=&scope=head|all&cursor=`：每页 50 个提交，带真实 parents；游标固定起始 OID 集合，引用名称是查询时标签。
- `GET /v1/git/commit?cwd=&oid=`：提交正文、父节点及第一父提交的变更路径。

Codex 字段依据本机 CLI 0.155.1 生成的 schema 与[官方 App Server 文档](https://learn.chatgpt.com/docs/app-server)。首轮实现支持 Codex，其他 provider 随能力实现后加入，不能将其当作已经兼容。
