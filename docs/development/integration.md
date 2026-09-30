# 模拟器与真实 SSH 主机联调

普通自动化验证：根目录 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`，电脑服务 `npm --prefix host-service test`。设备测试运行前用 `adb devices -l` 明确 serial，以下命令中的设备与路径由开发者替换。

`CredentialIntegrationTest` 验证 Android Keystore 加密、密钥恢复与删除。其 `sshIntegration` 需要显式 `sshPhase`：

1. `prepare`：在模拟器中创建测试密钥，公钥写入应用 cache 的 `integration.pub`，身份 ID 写入 `integration-id`。
2. 用 `adb exec-out run-as com.worldcopy.agentdeck cat cache/integration.pub` 读取公钥，在自己的测试账号授权。记录唯一注释，测试结束只删除该条授权。
3. `connect`：通过参数 `sshUser`、`sshHostKey`（OpenSSH 算法与 base64 公钥）核对并登录本机 `10.0.2.2:22`。自动通过 SSH 读取服务令牌，验证 SSH 转发、健康与原生会话列表。服务需已启动；自定义数据目录通过 `serviceDirectory` 指定，令牌不经测试参数传入。
4. `configure`：以相同参数保存“本机 Mac · 开发测试”主机，供 UI 测试使用。随后运行 `auto-token` 验证无缓存时自动认证、重连覆盖过期缓存；可用 `tokenFixtureDir` 指向独立测试目录，其中 `token` 内容为 `fixture-token`，`empty/token` 为空，`missing/token` 不存在，验证自定义目录读取和错误提示。
5. 远端 Ubuntu 使用 `sshHost`、`sshPort`、`sshName` 指定主机；同一次测试使用相同的 `sshFixture`，隔离生成的身份和缓存文件。`sshProject` 触发 Ubuntu 的 Git、真实命令与断线恢复测试，详见 [Ubuntu 验证](../validation/ubuntu.md)。
6. 服务器撤销测试公钥后，执行 `cleanup` 删除此 fixture 的手机主机配置、密钥和临时导出文件。

安装主 APK 与测试 APK：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

`WorkbenchSmokeTest` 仅在显式传入 `smokeProject` 时运行真实 API。该路径应为独立测试项目，不是正在开发的真实工作区。

```sh
adb -s emulator-5554 shell pm grant com.worldcopy.agentdeck android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.worldcopy.agentdeck.core.WorkbenchSmokeTest#reconnectReplaysRunAndBackgroundNotification \
  -e smokeProject /absolute/path/to/test-project \
  com.worldcopy.agentdeck.test/androidx.test.runner.AndroidJUnitRunner
```

测试方法：`codexChatThroughSsh` 验证新建与文字回复，可传 `smokeHost` 在多主机列表中选择目标主机；`reconnectReplaysRunAndBackgroundNotification` 在受管理命令执行期间断开 SSH、切到后台，确认结果恢复和完成通知；`voiceBecomesDraftAndSharedImageReachesCodex` 使用合成录音与红色图片，验证转写仅进入草稿、分享图片真正送到 Codex。

媒体测试需预先把有效 AAC/M4A 音频和 PNG 写入应用 cache 下的 `voice-test.m4a`、`image-test.png`，可用 `adb exec-in run-as ... sh -c 'cat > cache/文件名' < 本地文件`。电脑服务需配置语音环境。测试消耗当前 Codex API 配额，失败时结合设备 TestRunner 日志和具体会话结果定位；勿只重复执行来掩盖失败。

模拟器验证不替代物理手机的录音质量、锁屏、切网与功耗验收。首轮实测结论在 [验证记录](../validation/codex-workbench.md)。
