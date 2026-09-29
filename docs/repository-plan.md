# 仓库结构与管理设计

日期：2026-09-29。本文记录仓库结构与管理设计，已同步 Android Studio 初始化的工程布局；原始技术方案中的技术选型和 API 草案仍需阶段 0 验证。

## 1. 组织方式

采用单仓库，Android 客户端、电脑服务、协议和验证资料一起演进。一个功能需要同时改协议与两端时，放在同一改动中审阅。

初期 Android 使用一个 `app` 模块，电脑端使用一个 Node 工程。按职责分包即可；出现真实的复用、构建或发布需要时再拆独立模块。`providers` 和 Git 查询归电脑服务所有，先放在服务内部。G2 验证成功且开始写验证程序时创建 `glasses/`。

## 2. 目标目录

当前已有根目录 Gradle 工程、`app/`、管理文件、`.github/` 和 `docs/`；电脑服务、协议、样例及眼镜目录按开发进度创建。

```text
AgentDeck/
├── README.md
├── AGENTS.md
├── CONTRIBUTING.md
├── .gitignore
├── .gitattributes
├── .editorconfig
├── .github/
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/
│       ├── bug_report.md
│       └── feature_request.md
├── SSH_Agent_Android_Technical_Plan(1).md
├── docs/
│   ├── repository-plan.md
│   ├── roadmap.md
│   ├── development/wireless-adb.md
│   ├── decisions/             # 首次技术决策时创建
│   └── validation/            # 首次原型验证时创建
├── app/                      # Android 应用模块
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       ├── test/
│       └── androidTest/
├── gradle/
│   ├── libs.versions.toml
│   ├── gradle-daemon-jvm.properties
│   └── wrapper/
├── gradlew
├── gradlew.bat
├── gradle.properties
├── settings.gradle.kts
├── build.gradle.kts
├── host-service/             # Node + TypeScript 工程
│   ├── src/
│   │   ├── api/              # REST、SSE、请求解析
│   │   ├── execution/        # 任务归属、进程生命周期、审批
│   │   ├── providers/        # Codex、Claude 接口适配
│   │   ├── history/          # 发现、分页和原生历史读取
│   │   ├── git/              # Changes、Diff、Graph 查询
│   │   ├── storage/          # 事件与数据库访问
│   │   └── attachments/      # 图片与录音生命周期
│   ├── test/
│   ├── package.json
│   └── package-lock.json
├── protocol/                 # 实际采用的协议定义和跨端样例
├── fixtures/                 # 合成或脱敏的会话、事件、Git 构造数据
├── scripts/                  # 已经重复使用的开发、打包命令
└── glasses/                  # G2 验证程序，随后按验证结果组织
```

`host-service` 暂按 npm 单工程设计，提交 lockfile。若选定底座自带包管理器，沿用它并更新此处；一个工程只维护一套锁文件。Gradle Wrapper 的脚本、JAR、配置和版本目录均入库，保证其他机器能使用相同构建入口。

Android 当前 namespace 和 application ID 均为 `com.worldcopy.agentdeck`。包内随功能增加 `feature/hosts`、`feature/sessions`、`feature/chat`、`feature/git` 和 `core/ssh`、`core/network`、`core/storage`、`core/notifications`。媒体与 G2 随对应阶段增加。

## 3. 依赖与数据边界

Android 页面经 ViewModel 调用数据层；SSH、网络和缓存由数据层协调。页面负责交互和呈现，电脑端负责执行归属、原生历史和真实 Git 查询。

`host-service/api` 组织业务调用；Agent SDK 依赖留在各 provider 内，Git CLI 调用留在 `git/`。协议定义客户端真正消费的数据，避免把整个 SDK 类型直接作为跨端接口。

`protocol/` 从第一条跑通的请求与事件开始记录 Schema 和样例。先验证双端可用，再决定是否生成类型。共享样例放 `fixtures/`，只被单个测试使用的样例留在对应工程测试目录。

服务运行数据、手机缓存、上传附件与开发者诊断输出放在各自运行目录，不放进源代码树。仓库内需要临时留存的真机结果使用被忽略的 `.local/`；可复现结论写入 `docs/validation/`，隐去设备地址、身份和会话正文。

## 4. 与原始方案的实现调整

原方案第 14 节的逻辑职责保留，初期把 `providers/` 与 `git-service/` 合并到电脑服务内，减少独立包及版本管理成本。

Android Studio 工程采用仓库根目录 Gradle 工程与 `app/` 模块布局。原方案第 14 节的 Android 职责由这里承载，开发入口和命令以当前布局为准。

第 10、11 节要求附件哈希，但没有说明它替代的昂贵操作或改变的后续决策。首版用附件 ID、大小限制、上传完成状态及实际解码结果处理附件；若以后引入内容去重，再依据收益决定是否计算哈希。SSH 主机身份核验和 Git 固有 OID 属于现有协议语义，保留。

第 5、13 节的 SSH 身份核验、凭据保存及具体审批绑定属于产品本身的要求。第 10 节的兼容窗口、文件解析兼容和第 14 节的迁移交付随真实版本、真实数据落地。阶段 0 先固定验证版本；首次需要保存并升级已有数据库时再加入实际迁移。

原方案列出的上游项目尚未在本仓库编译验证。选型决策记录候选 commit、构建结果、许可证、必要改造和选择理由，随后冻结依赖。文档中的链接与建议是设计材料，不能自动触发下载安装、服务部署或代码引入。

## 5. Git 与协作

默认分支为 `main`，远端 `origin` 为 `git@github.com:TongZhe2016/AgentDeck.git`，托管于 GitHub。

开发采用短分支，例如 `feat/ssh-connect`、`fix/event-resume`、`docs/repository-layout`。单人文档维护可以直接提交；涉及功能与协议的改动通过 PR 留下问题、结果和验证记录。提交建议使用 `feat:`、`fix:`、`docs:`、`test:`、`chore:`，无需额外提交钩子强制格式。

Agent 执行长程任务时，每完成一个有明确结果的子任务即 commit 并 push 当前工作分支，持续备份进度；具体约定见根目录 `AGENTS.md`。

Git 跟踪源代码、构建入口、依赖锁文件和脱敏样例。忽略本机 SDK 路径、IDE 状态、构建缓存、依赖目录、签名密钥与运行数据。忽略规则按实际目录写，避免误伤测试数据库、Gradle Wrapper 和正常图片资产。

GitHub 模板已放入 `.github/`，建远端后即可使用。初期使用 issue、milestone 和 PR 即可。Milestone 对应阶段 0–4；有多人稳定分工后再添加 `CODEOWNERS`。许可证确定后创建 `LICENSE`，开始对外发布后维护 `CHANGELOG.md`。

## 6. 验证、CI 与发布

首个可编译工程落地时同时加入 CI，使用该工程已经跑通的命令。Android 执行 debug 构建、单元测试和 lint；电脑服务执行类型检查、单元测试与构建。跨端协议改动使用双方消费的同一批样例验证。

物理手机负责 SSH 连通、配对安装、断线恢复、锁屏与通知等实机行为。CI 结果不能代替真机结果。验证记录写清版本、环境、操作、观察结果和未解决项；每次运行应能指出具体失败以及失败后将如何调整。

阶段 0 完成前先用 debug APK 测试。首次发布前确定签名、电脑端安装方式和兼容组合；签名材料由发布环境保存。初期每次发布使用统一仓库版本标签，例如 `v0.1.0`，同时列出 APK、服务版本、协议版本及验证过的组合。
