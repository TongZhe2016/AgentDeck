# AgentDeck protocol 1

HTTP 与 SSE 仅经 SSH loopback 转发访问。每个请求必须携带 `Authorization: Bearer <电脑服务令牌>`。电脑服务监听 `127.0.0.1:4317`。Android 在 SSH 认证成功后读取服务数据目录下的 `token` 文件（默认 `~/.agentdeck/token`），每次服务连接／重连重新获取并加密缓存。HTTP 与 SSE 请求仍由客户端自动携带令牌，SSE 本身不承担身份认证。错误响应为 `{ "error": "可显示的原因" }`。

- `GET /v1/health`：协议版本、服务版本和平台。
- `GET /v1/snapshot`：最近执行、待处理请求、当前事件游标。
- `GET /v1/events?after=<seq>`：SSE，每条 `data` 包含 `{seq,type,data,createdAt}`，严格按序补发。客户端使用最后成功处理的序号恢复；无效游标需重取快照。事件类型为 `run.updated`、`agent.event`、`approval.requested`、`approval.resolved`。
- `GET /v1/sessions?search=&cursor=`：Codex 原生会话标题搜索、分页，每页 40 条轻量摘要（id、name、preview、cwd、updatedAt、model、reasoningEffort、managed），不返回正文。使用本机状态数据库索引，避免每次列表查询扫描历史文件修复元数据。客户端连接时只取第一页，保留已缓存摘要，用户按需继续分页。查询不恢复执行。
- `GET /v1/models?cwd=`：返回此电脑 Codex 的完整分页模型目录 `data`（model、displayName、supportedReasoningEfforts、defaultReasoningEffort 等）和当前项目的 `defaults`。不返回其余电脑配置。
- `POST /v1/sessions {cwd}`：创建受管理 Codex 会话，初始采用 workspace-write 沙箱和 on-request 审批，返回实际 `executionSettings`。
- `POST /v1/sessions/:id/settings {model,effort,permissionMode}`：保存此会话下一轮的执行设置，返回 `{executionSettings}`。仅受管理且空闲的会话可修改；按电脑返回的模型目录检查思考强度。`permissionMode` 支持 `read-only`（read-only / never）、`on-request`（workspace-write / on-request）、`untrusted`（workspace-write / untrusted）、`never`（workspace-write / never，越权操作失败）、`full-access`（danger-full-access / never）。设置保存在服务数据库，第一条消息发送前也可修改。恢复会话及每轮执行均传给 Codex；不修改全局 config.toml。
- `GET /v1/sessions/:id?cursor=`：只读历史、managed 标记及已保存的 executionSettings，每页 20 个完整轮次，页内按时间升序；nextCursor 向更早历史翻页。
- `POST /v1/search {query,project?,cursor?}`：原生正文搜索，每次最多十页，返回命中消息摘要、会话及继续扫描的游标；取消连接会停止后续扫描。
- `POST /v1/sessions/:id/resume {confirmStopped:true}`：用户确认原有执行已停止后恢复原生 ID，沿用已保存设置。恢复时不返回完整历史，正文仍按需分页。
- `POST /v1/runs {clientRequestId,threadId,text,attachments?:[id]}`：先保存请求身份，再派发。相同 ID 和内容返回已有结果；同 ID 不同内容报错；同会话活动执行互斥。
- `POST /v1/attachments/:id?threadId=`：上传图片／录音二进制，Content-Type 指定类型，上限 10 MiB；同 ID 重试返回原附件，只能用于所属会话。
- `GET /v1/attachments/:id`、`DELETE /v1/attachments/:id`：读取／删除附件；已被执行引用的图片保留供原生历史恢复。
- `POST /v1/transcriptions {attachmentId,threadId}`：本地录音转写，返回 `{text}`，相同录音成功结果可重复读取。不会启动 Codex turn。手机确认文字后发送；原始音频不能直接用于 `/runs`。
- `POST /v1/runs/:id/cancel`：取消具体 turn。
- `POST /v1/runs/:id/reconcile`：根据原生历史核实 unknown 执行，不重新发送。
- `POST /v1/approvals/:id {decision:"accept"|"decline"}`：只处理仍有效的具体请求。问题使用 `{answers:{questionId:{answers:["answer"]}}}`。
- `GET /v1/git/status?cwd=`：分支、HEAD 可达计数、浅克隆、上游方向、唯一路径计数及分组变更。
- `GET /v1/git/diff?cwd=&path=&group=`：staged／unstaged／untracked／conflict；历史差异增加 commit 和 parent（从 0 开始）。上限 512 KiB，返回 truncated。
- `GET /v1/git/graph?cwd=&scope=head|all&cursor=`：每页 50 个提交，带真实 parents；游标固定起始 OID 集合，引用名称是查询时标签。
- `GET /v1/git/commit?cwd=&oid=&parent=0`：提交正文、作者、时间、父节点及选定父提交的变更路径。

Codex 字段依据本机 CLI 0.159.2 生成的 schema 与[官方 App Server 文档](https://learn.chatgpt.com/docs/app-server)。首轮实现支持 Codex，其他 provider 随能力实现后加入，不能将其当作已经兼容。
