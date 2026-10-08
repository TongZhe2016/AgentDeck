# AgentDeck protocol 1

HTTP 与 SSE 仅经 SSH loopback 转发访问。每个请求必须携带 `Authorization: Bearer <电脑服务令牌>`。电脑服务监听 `127.0.0.1:4317`。Android 在 SSH 认证成功后读取服务数据目录下的 `token` 文件（默认 `~/.agentdeck/token`），每次服务连接／重连重新获取并加密缓存。HTTP 与 SSE 请求仍由客户端自动携带令牌，SSE 本身不承担身份认证。错误响应为 `{ "error": "可显示的原因" }`。

- `GET /v1/health`：协议版本、服务版本、平台和 `catalog {ready,total,syncedAt,error}` 状态。
- `GET /v1/snapshot`：最近执行、待处理请求、当前事件游标。
- `GET /v1/events?after=<seq>`：SSE，每条 `data` 包含 `{seq,type,data,createdAt}`，严格按序补发。客户端使用最后成功处理的序号恢复；无效游标需重取快照。事件类型为 `run.updated`、`agent.event`、`approval.requested`、`approval.resolved`、`sessions.changed`。目录事件的 `data` 为 `{upserted:[摘要],removed:[id]}`，每批新增／更新及删除各至多 40 条；客户端合并列表并保留正在阅读的正文与草稿。
- `GET /v1/sessions?search=&cursor=&after=`：读取服务持久保存的完整未归档目录，每页 40 条轻量摘要（id、name、preview、cwd、updatedAt、model、reasoningEffort、managed），不返回正文。标题／预览搜索在该目录中进行，分页使用更新时间与 ID 组成的不透明游标。返回 `data,nextCursor,catalog,catalogCursor,changes,reset`；`after` 为手机上次保存的 `catalogCursor` 或目录事件序号，`changes` 合并此后所有目录事件为 `{upserted,removed}`，用于补齐离线变更。首次请求省略 after，仅返回当前页；客户端首次获得目录游标时以该页替换没有同步游标的旧摘要缓存；游标超出重建后的服务事件库时 reset=true，客户端重建摘要缓存。服务独立按约 2 秒检查最近原生索引、每分钟完整校准；使用 `useStateDbOnly`，不因手机连接扫描正文。缓存已有时后台失败仍可读取，并在 catalog.error 提示原因。纯空白会话在 Codex 首次落盘前不可发现。
- `GET /v1/files?path=&cwd=`：下载当前 SSH 账号可读取的普通文件。绝对路径直接读取，相对路径按会话绝对 cwd 解析；流式返回原始字节、Content-Length 和 UTF-8 文件名，不恢复 Codex 会话。文件不存在、无读取权限或路径为目录时返回错误。Android 仅在用户点击文件引用并选择保存位置后请求，支持取消并清理未完成文件。
- `GET /v1/models?cwd=`：返回此电脑 Codex 的完整分页模型目录 `data`（model、displayName、supportedReasoningEfforts、defaultReasoningEffort 等）和当前项目的 `defaults`。不返回其余电脑配置。
- `POST /v1/sessions {cwd}`：创建受管理 Codex 会话，初始采用 workspace-write 沙箱和 on-request 审批，返回实际 `executionSettings`。
- `POST /v1/sessions/:id/settings {model,effort,permissionMode}`：保存此会话下一轮的执行设置，返回 `{executionSettings}`。仅受管理且空闲的会话可修改；按电脑返回的模型目录检查思考强度。`permissionMode` 支持 `read-only`（read-only / never）、`on-request`（workspace-write / on-request）、`untrusted`（workspace-write / untrusted）、`never`（workspace-write / never，越权操作失败）、`full-access`（danger-full-access / never）。设置保存在服务数据库，第一条消息发送前也可修改。恢复会话及每轮执行均传给 Codex；不修改全局 config.toml。
- `GET /v1/sessions/:id?cursor=`：只读历史、managed 标记及已保存的 executionSettings，每页 20 个完整轮次，页内按时间升序；nextCursor 向更早历史翻页，liveCursor 用于当前会话的增量正文跟随。
- `GET /v1/sessions/:id/updates?cursor=`：只读当前会话的已存消息，不恢复或取得写入权。返回 `{turns,liveCursor,more}`，客户端按轮次／消息 ID 合并，完整消息覆盖临时文字；不要丢弃此前已加载历史。首次无 cursor 读取最新 20 轮，此后用 liveCursor 重读末轮并向更新轮次分页，每页最多 20 轮，more=true 时继续补页。服务先取得最新轮次的原生 backwardsCursor，再读取增量，避免新轮次在取锚点时被跳过。客户端约每秒读取，未变化的正文不重复解析；关闭会话停止读取。Codex 0.161.0 跨进程只能读取已落盘消息，尚未暴露其他写入者未完成消息的实时片段。
- `POST /v1/search {query,project?,cursor?}`：原生正文搜索，每次最多十页，返回命中消息摘要、会话及继续扫描的游标；取消连接会停止后续扫描。
- `POST /v1/sessions/:id/resume {confirmStopped:true}`：恢复原生 ID，沿用已保存设置。Android 在用户点击“继续对话”或为历史会话保存设置时调用；服务检查活动执行状态，仍在运行则拒绝恢复。仅浏览历史不调用此接口。恢复时不返回完整历史，正文仍按需分页。
- `POST /v1/sessions/:id/takeover`：通过原生 resume 尝试取得写入权；成功返回 `{acquired:true,thread}`，其中 thread 含实际设置、managed=true、writerState=owned；其他写入者仍占用时返回 `{acquired:false,writerState:"external",message:"有其他用户正在使用"}`。不终止其他进程或删除写入锁。本服务活动执行须先结束或核实。
- `POST /v1/sessions/:id/fork`：原生 fork 已存历史到新 ID，返回新会话及实际设置、managed=true、writerState=owned；原会话保持原状。
- 读取会话详情附带 `writerState`：owned（本服务已加载）、external（存在其他端写入锁提示）、available（未发现写入锁）。锁存在不等同于仍被持有，接管接口给出最终结果。恢复／设置时发现 Active Writer 返回 HTTP 400、`code:"active_writer"`；执行派发失败返回的 run 附带 `writerState:"external"`，客户端保留草稿与附件并回到接管入口。
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
