# 贡献指南

先阅读 [仓库设计](docs/repository-plan.md) 和 [实施路线](docs/roadmap.md)。当前维护 Android + Codex 工作台及 macOS 电脑端服务。

## 提交改动

围绕一个可说明的问题组织改动，写清完成后用户能做什么。小修复可以直接提交 PR；会改变通信方式、模块边界或首版范围的改动，先在 issue 说明方案。

分支使用 `feat/`、`fix/`、`docs/` 等简短前缀。提交说明使用具体动词；推荐 `docs: document wireless ADB setup` 这样的格式。PR 说明问题、结果和相关验证；未完成的验证直接注明。

## 开发与验证

Android Studio 打开仓库根目录，应用模块为 `app/`，包名为 `com.worldcopy.agentdeck`。电脑服务在 `host-service/`。

Gradle 版本由 `gradle/wrapper/gradle-wrapper.properties` 固定，AGP／Compose 等依赖由 `gradle/libs.versions.toml` 管理，构建 JVM 条件由 `gradle/gradle-daemon-jvm.properties` 声明。本机 Android SDK 路径放入被忽略的 `local.properties`，不要把本机绝对路径写入共享构建配置。

以下命令从仓库根目录执行，按改动选择需要的项。macOS 未配置终端 Java 时，可使用 Android Studio 自带 JBR 设置 `JAVA_HOME`。构建 SDK 包名为 `platforms;android-37.0`。

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
npm --prefix host-service ci
npm --prefix host-service test
```

设备测试使用 `./gradlew :app:connectedDebugAndroidTest`，执行前明确测试设备。Gradle 设备测试可能卸载应用；需要保留联调主机与密钥时，安装 app／androidTest APK 后使用指定 serial 的 `adb shell am instrument`。真实 Mac 测试需显式传入测试项目等参数，默认跳过；步骤见 [开发测试](docs/development/integration.md)。

运行任何检查之前，先说明：它检测什么具体失败，失败会让下一步采取什么不同动作。根据改动选择验证：协议需双端消费样例，进程管理需断线后执行验证，纯文档改动检查路径和叙述即可。针对实际行为编写测试，避免只重复实现细节。

Wi-Fi ADB 使用 [真机指南](docs/development/wireless-adb.md)。多设备时用 `adb -s` 指定目标；真机结果写入验证记录，注明设备系统与构建版本。

## 资料与依赖

提交锁文件与可复现样例。真实会话、SSH 私钥、模型凭据、签名材料、配对码及本机路径配置留在本地。需共享的错误日志先删去个人信息和会话内容。

引入第三方代码时记录仓库、commit、许可证及需保留的声明。新依赖应解决当前需求，解释采用原因。

影响架构的决定写入 `docs/decisions/NNNN-topic.md`，包含背景、决定和影响。首次写决定时创建目录；已经确定的决定只在需求或证据变化时重新讨论。
