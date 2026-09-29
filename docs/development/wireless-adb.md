# Wi-Fi ADB 真机调试

ADB 用于开发机安装、调试 Android App；产品与目标电脑的业务通信使用 SSH，两条链路分别验证。

## 本机状态

2026-09-29 已确认本机存在 Android SDK Platform-Tools 37.0.1，ADB 可启动。当前 shell 的 `PATH` 未包含 `adb`，可在终端会话中设置：

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

2026-09-29 已完成 Wi-Fi 配对、自动连接与只读 shell 验证。设备为 vivo V2502A，Android 16（API 36）；`adb devices -l` 显示无线 TLS 设备为 `device`，通过该无线标识执行 `getprop` 成功返回型号和系统版本。应用安装与启动待首个 APK 构建后验证。

本次曾出现 `protocol fault (couldn't read status message)` 和 ADB 的 `No route to host`，但普通 TCP 探测能通。确认没有已连接设备后，执行 `adb kill-server`、`adb start-server`，使用仍有效的配对信息重试成功。已观察到重启服务解决本次故障，具体根因未确定。以后仅在出现相同现象时考虑这一处理；重启 ADB 服务会中断该 Mac 上其他设备的调试连接。

## Android 11 及以上：配对

1. 手机和 Mac 连接同一 Wi-Fi。
2. 在手机开启开发者选项，进入「无线调试」并开启。
3. 点击「使用配对码配对设备」，保持弹窗打开，读取 IP、配对端口和六位配对码。
4. Mac 执行以下命令，将占位内容换成弹窗地址；按提示输入配对码。

```sh
adb pair <手机IP>:<配对端口>
```

配对成功后返回无线调试主页，读取该页的「IP 地址和端口」。连接端口与配对端口用途不同：

```sh
adb connect <手机IP>:<连接端口>
adb devices -l
```

如果 ADB 已自动连接，可直接选用列表中的设备标识。mDNS 自动发现不可用时仍可使用上面的显式地址连接。

## 验证与测试

这一步检测无线传输是否可真正执行设备命令。若设备不在线则处理连接；若显示 `unauthorized` 则处理手机授权；只有 shell 成功后再进入安装流程。

从 `adb devices -l` 复制无线设备的完整标识，替换下面的 `<无线设备标识>`。有 USB 连接时先拔掉线，再执行：

```sh
adb -s <无线设备标识> shell getprop ro.product.model
adb -s <无线设备标识> shell getprop ro.build.version.release
```

成功应同时满足：设备为 `device`、通过无线标识返回正确型号与 Android 版本。当前还没有 APK，应用安装与启动验证在首个 debug 构建完成后进行：

```sh
adb -s <无线设备标识> install -r <debug-apk路径>
```

在 Android Studio 的设备选择器中选择同一手机，随后使用 Run 或 Debug。记录实际包名与构建版本。

## 重连与故障定位

无线调试重新开启或网络变化后，先查看手机主页的当前连接地址，再执行 `adb connect`。已配对设备通常无需重新配对；手机移除配对记录后重新执行配对步骤。

找不到设备时运行 `adb mdns services`，判断是否只是自动发现缺失。显式连接也失败时，核对当前地址和两端网络，检查 Wi-Fi 是否隔离客户端；可换到允许设备互通的网络。配对失败时重新打开配对弹窗，使用新的配对端口与配对码。

Android 10 及以下走 USB 初始连接方案：先接线并完成 USB 调试授权，用 `adb -s <USB设备标识> tcpip 5555` 开启 TCP 调试，再执行 `adb connect <手机IP>:5555`，拔线验证。结束后可通过该无线标识执行 `adb -s <手机IP>:5555 usb` 恢复 USB 模式。

临时配对码与设备具体地址保留在本地。需要留存截图、日志时放入 `.local/`，共享验证记录只保留版本、步骤与结果。

参考：[Android 官方 ADB 指南](https://developer.android.com/tools/adb)。
