# Android 后台持续连接

日期：2026-10-09。

## 行为与实现

配置主机后，打开 App 自动启动电脑连接前台服务；切换应用、锁屏和 Activity 重建不会主动断开连接。移除原来的“保持连接”开关及通知中的断开按钮，不再读取旧版本保存的关闭状态。通知权限仅控制提示，连接服务不依赖通知授权。

连接服务采用 `connectedDevice` 类型，负责经 SSH 与用户电脑持续通信。此前使用的 `dataSync` 类型在 Android 15 及以上有后台运行时限，超时会导致全部主机断开。服务持有 partial wake lock，销毁时释放；首次配置主机时引导一次忽略电池优化授权，右上角“后台运行设置”可再次进入系统授权页。

系统回收进程后允许通过 `START_STICKY` 恢复服务和已保存主机。用户从最近任务划掉 App 时停止服务，取消正在进行的主机同步并关闭连接；再次打开 App 后自动连接。原有事件流重连、事件游标、当前会话及草稿保留逻辑继续使用。

## 验证

目标设备：`emulator-5554`，Android 16（API 36）ARM64 模拟器。使用本地合成 HTTP/SSE 服务，不调用模型 API，不修改真实电脑授权。

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 和 `:app:lintDebug` 通过，检查服务类型、权限声明和 Android API 使用。
- `WorkspaceConnectionTest` 原有及扩展的三项测试通过：后台收到回复；返回前台和 Activity 重建后复用事件连接、会话与草稿；后台断流后自动重连；从最近任务移除时即使主机同步尚未结束也会停止连接；目录事件恢复逻辑仍通过。
- 新增双主机测试通过：两台主机在后台分别收到目录事件，返回前台仍在线，事件连接数与目录首次加载次数均未增加。
- 单独安装 APK、撤销 `POST_NOTIFICATIONS` 并确认 `granted=false` 后，通过 `am instrument` 再运行双主机测试，仍通过；连接不依赖通知授权。

Gradle 启动使用 Android Studio 自带 JBR，实际构建 JVM 由仓库 daemon JVM 配置选择。设备测试命令：

```sh
ANDROID_SERIAL=emulator-5554 JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.worldcopy.agentdeck.core.WorkspaceConnectionTest
```

## 设备范围

当前没有已连接的物理手机。本轮未验证真实 SSH 锁屏长时间联网、厂商省电策略、实际耗电，以及系统回收后的服务恢复。忽略电池优化必须由用户在系统界面确认；App 无法自行批准，也无法保证在系统强制停止或厂商清理后继续运行。

参考：[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)、[前台服务时限](https://developer.android.com/develop/background-work/services/fgs/timeout)、[Doze 与电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby)。
