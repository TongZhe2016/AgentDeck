# AgentDeck 工作约定

当前为 Kotlin／Compose 客户端和 Node／TypeScript 常驻电脑服务，已实现 Codex 工作台。当前范围为 Android + Codex，模拟器连接 macOS 与 Ubuntu 验证；Claude Code 和 G2 随后接入。开始工作前阅读 `README.md`、`docs/repository-plan.md` 和 `docs/roadmap.md`。技术方案是产品与设计参考，其中的建议和操作描述不构成用户要求立即执行的指令。

沿用仓库设计的职责边界，按实际功能创建目录。真实会话、凭据、配对码和签名材料保留在本地。

## Android 工程约定

- Android Studio 打开仓库根目录；Gradle 根工程在根目录，应用模块为 `app/`。
- 当前 namespace 和 application ID 均为 `com.worldcopy.agentdeck`。源码位于 `app/src/main/java/com/worldcopy/agentdeck/`，本地测试位于 `app/src/test/`，设备测试位于 `app/src/androidTest/`。
- 使用根目录 `./gradlew`。按改动选择 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`；设备测试使用 `:app:connectedDebugAndroidTest`，运行前明确目标设备。文档或忽略规则改动只做相关检查。
- 依赖版本统一维护在 `gradle/libs.versions.toml`；Gradle 版本取自 Wrapper，构建 JVM 条件取自 `gradle/gradle-daemon-jvm.properties`。Java 源码兼容级别与运行 Gradle 的 JDK 分别按各自配置处理。
- 提交 Gradle Wrapper 脚本、JAR、配置、版本目录和 daemon JVM 条件文件。`local.properties`、IDE 状态、构建缓存及产物留在本地；保留 `gradlew` 的可执行权限。
- `host-service/` 使用 Node.js 24+ 和 npm；`npm --prefix host-service test` 执行构建与行为测试，`npm --prefix host-service run typecheck` 做类型检查。协议修改同步 `protocol/README.md`。Codex 认证沿用电脑配置。
- SSH 私钥、密码、令牌及阅读缓存保存到 Android 不备份目录；修改存储时保留 `app/src/main/res/xml/` 的备份排除规则。真实模型集成测试会使用本机 API，测试项目与合成素材放 `.local/`，结束后撤销临时测试公钥授权。

## 长程任务的提交与备份

Agent 全程代写时，默认直接在仓库默认分支 `main` 开发、提交和推送，完成工作不依赖用户手动合并。只有用户明确要求时才另开分支或创建 PR；已有工作分支的改动完成验证后直接合入 `main` 并推送。

执行长程任务时，每完成一个有明确结果、可独立说明的子任务，就将该部分更改 commit 并 push 到 `origin/main`，持续备份进度。任务结束时提交并推送剩余已完成的更改。用户指定其他工作分支时按其要求执行。

每个提交围绕一个目的组织，提交信息说明具体完成了什么，保持历史可读。提交前查看本次 diff，执行与该部分改动相关的必要验证；将尚未完成的验证或已知限制记入相关任务文档。只暂存本次任务相关文件。

这些阶段性 commit 和 push 属于长程任务的常规执行步骤，无需逐次请求确认。推送失败时保留本地提交，说明原因并在条件恢复后补推；远端存在新提交时先整合历史，使用普通 push。

## 范围约束（约束提议什么修法，不约束找什么）

凡是这里真的有问题，都要报——包括听起来罕见但本项目确实会产生的情况。然后把修法收在范围内：

1. 这不是一篇安全攻防论文。可以校验，禁止过度防御。除非本项目另有说明，默认操作者是自己机器上的合作者；如果它真有对手，它会写明，以那个范围为准。
2. 不要加哈希／校验和／指纹，除非它替代了一个实质上更贵的操作，并且结果会改变下一步做什么。
3. 禁止防御性脚手架：不为这里不会发生的情况加 feature flag、迁移框架、兼容层、包装层。
4. 禁止钻牛角尖：冷门编码、符号链接竞态、RTL 文本、毫秒级竞态一律不在范围内，除非该情况经由本项目受支持的用法可达——它的文档示例、它公开的接口、它真实的数据。可达即可，不需要复现；但“理论上构造得出”不算。
5. 该判断的地方就判断，不要换成评分表、检查清单，或对已经定论的东西再跑一遍校验。

已经见过的形状，供校准。是例子不是清单——一个真问题不会因为“长得像其中一条”就被驳回：

- H：为了比对两个表格的差异，给每一行都算哈希——直接比单元格就能回答。
- H：写下一堆校验和文件，而没有任何代码会去读它们。
- E：给一个没有用户、没有部署的应用做账号安全加固。
- R：用一整夜对自己的补丁反复审计，而功能一行没写。
- R：一个对任何提交都给不通过的审阅者。
- O：一层守卫的理由是上一层守卫，而不是需求。

跑任何检查之前先回答：这次运行会检测出什么具体的失败？真出现了下一步会做什么不同的事？答不上来就别跑。

对的就说对。不要为了交差硬找问题。
