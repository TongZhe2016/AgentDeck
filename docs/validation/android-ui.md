# Android 界面验证

2026-10-01：Material 3 品牌主题、项目／主机／密钥／待处理页、聊天输入区与全屏 Diff。

## 已完成

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 通过。
- `:app:testDebugUnitTest` 的 8 项本地测试通过。
- Android 模拟器 375 × 750dp、浅色、系统字号：`UiLayoutTest` 通过项目展开、进入历史、编辑草稿、附件菜单、打开／关闭 Diff、主机编辑入口、密钥导入入口及待处理导航。
- `ProjectHomeTest` 两项、`CommitGraphTest` 一项、`KeyImportScreenTest` 两项通过，覆盖跨主机分组、提交展开和实际密钥导入行为。
- 品牌配色正文、次级正文、primary 和 primaryContainer 前景／背景的浅深色对比度均超过 4.5:1。

布局测试只在模拟器写入合成主机、会话和 Git 缓存，结束后清理。截图与构建日志留在忽略的 `.local/ui-refresh/`。本次未调用真实模型 API；SSH 和电脑服务未变更。

## 适配与安装

同一布局流程还通过模拟器的以下配置，并检查了页面截图：

- 375 × 750dp，深色，2 倍系统字号：导航、输入、附件菜单、主机编辑与密钥导入可访问。
- 750 × 375dp，深色横屏：侧边导航、聊天输入和全屏 Diff 可访问。
- 800 × 1100dp，深色宽屏：使用侧边导航，项目列表与聊天正常显示。

大字号检查发现工作台英文标签会拆行，改为单行滚动标签；横屏检查发现重复工具栏挤占聊天空间，进入工作台后仅保留主机工具栏。

最新 debug APK 已通过 USB 覆盖安装到 vivo V2502A，ADB 安装返回 `Success`，启动 `MainActivity` 返回 `Status: ok`。手机保留已有配置。实际 SSH 对话、相机与录音的新版人工验收由用户在设备上继续进行。

## 主机与项目两层折叠

2026-10-01：项目页更新为“主机 → 项目 → 对话”。主机与项目默认折叠，收起主机同时收起其项目；一级显示主机名与汇总数量，项目和对话逐级缩进。

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过。
- 模拟器上两项指定测试通过：`ProjectHomeTest#homeCollapsesHostsAndProjectsAndOpensConversationOnItsOwnHost`、`UiLayoutTest#navigationProjectChatAndDiffRemainReachable`。
- 验证主机和项目默认收起、展开主机后项目仍收起、项目各自展开、收起主机隐藏全部子项、再次展开主机时项目恢复收起，以及跨主机同名项目／相同对话 ID 的正确导航。返回首页时两层恢复折叠，新建对话使用选中项目的路径。
- 检查主机折叠、主机展开与项目展开的浅色截图，缩进、数量、路径和对话入口正常。日志与截图保存在忽略的 `.local/project-hierarchy/`。
- 新版已通过 USB 更新到 vivo V2502A，安装返回 `Success`。手机上的测试主机为零；本轮界面自动化验收在模拟器完成。

## 打开 App 自动同步

2026-10-01：在 Activity `onStart` 复用项目同步流程，打开 App、回到前台和 Activity 重建时自动尝试连接与同步。先恢复离线索引，失败保留缓存并继续处理其他主机；主机身份提示按主机排队，确认后继续读取服务与项目。已在线的主机复用 SSH 隧道刷新项目，正在进行的主机管理操作沿用已有 busy 限制。

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过。
- 模拟器通过 `StartupSyncTest#launchAndForegroundSyncWithoutManualConnection`，经真实 SSH 连接本机常驻服务，自动获取令牌并读取真实 Codex 项目。测试没有手动调用连接或同步入口。
- 验证排在前面的连接失败主机仍保留缓存，两台待确认主机依次提示，并继续同步后面的可用主机；确认第一台后自动连接与同步。
- 验证 Activity 重建和后台回到前台均刷新项目，同一在线主机的转发端口不变、当前会话与草稿保留。
- 测试主机与缓存已清理，临时公钥授权已撤销；日志留在忽略的 `.local/startup-sync/`。本轮没有发起真实模型任务。

vivo V2502A 解锁后已通过 USB 更新此次新版，安装返回 `Success`，打开 `MainActivity` 返回 `Status: ok`。已有配置保留；上述自动同步行为验收在模拟器完成。

真机打开新版后，macbook、eez75、eez076、eez144、eez145 的项目缓存时间已刷新。tz-4090 的缓存仍保留；其手机地址、端口、用户名与本机 SSH 别名一致，已保存主机公钥与部署时读取的公钥相同，但从 Mac 连接该 SSH 入口也返回 `Connection refused`，待该入口恢复后再同步。
