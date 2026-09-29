# Codex Android 工作台验证

验证环境：macOS arm64、Codex CLI 0.155.1（电脑现有 API 配置）、Node.js 26.9.0、Android API 36 ARM64 模拟器。首轮记录日期 2026-09-30。

已完成：

- 模拟器生成 Ed25519 身份，经加密存储恢复，真实 SSH 登录本机 Mac；核对主机公钥后转发到仅监听回环地址的电脑服务。
- 读取现有 Codex 会话；在隔离测试项目创建会话、发送消息并得到预期文字回复。
- Compose 界面测试完整走过连接、进入工作台、新建会话、发送和收到 `AGENTDECK_UI_OK`。设备 TestRunner 记录 1 项测试、0 失败；ADB instrumentation 回传出现 Binder watcher 中断，不能以其退出码单独判断测试结果。
- Android debug/APK 测试包构建、JVM 单元测试及 lint 通过。电脑服务 7 项测试覆盖事件恢复、重复请求、审批失效、重启未知状态、Git 合并图与分页、附件归属及正文搜索。
- Git 查询测试覆盖暂存与未暂存同时存在、重命名、未跟踪文件、增删行数、合并父节点及分页期间新提交。
- 第二项真实 Compose 测试：Codex 执行 `sleep 12` 期间断开 SSH 并将模拟器切到 Home，电脑继续执行；客户端重连恢复正确结果、仅有一次受管理执行，并收到完成通知。instrumentation 返回 `OK (1 test)`。
- faster-whisper 1.2.1、多语言 base 模型在本机 CPU 完成本地 WAV 转写，HTTP 接口返回文字并成功删除临时录音。合成语音中的专有名词存在误识别；手机必须确认文字后发送。
- 上传 256×256 红色 PNG，经 `localImage` 交给本机 Codex API 配置，模型正确回复 `The image is a solid bright red square.`。早先微小图片未加载的结果由更明确的图片测试补充，不能据此判定 provider 不支持视觉。
- 手机媒体集成测试通过 `OK (1 test)`：M4A 上传转写为可编辑草稿且没有产生 Codex 执行；Android 分享 PNG 后手机转换 JPEG，模型成功识别红色。
- 真实 Codex 发出 `item/commandExecution/requestApproval`，服务拒绝审批后正常结束；取消另一次运行中的命令得到 `interrupted`。正文搜索在隔离测试项目返回 11 条命中和继续扫描游标。
- Git 界面测试通过：在 Graph 页完整编辑电脑路径，再查询真实 AgentDeck 仓库并切换 Changes，检查实际工作树身份。该测试修复了路径输入触发过早查询，以及加载 Graph 时提交列表与图节点使用不同快照导致的越界崩溃。

当前限制：

- 图片上传与模型读取已通过本机 API 配置验证；其他 provider 的视觉支持取决于其模型。语音采用独立电脑端转写，不依赖 Codex 读取音频。
- 后台通知和真实 SSH 断线重连已通过上述模拟器测试。物理手机的锁屏、切网、麦克风准确率和功耗未验证。
- Claude Code 和 G2 按当前范围留待后续；macOS 之外的电脑未做实机验证。

真实会话、测试音视频和服务令牌均留在被忽略的 `.local/`，测试公钥授权在联调结束后撤销。
