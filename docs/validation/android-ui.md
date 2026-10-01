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

## 项目页主机连接状态

2026-10-01：主机行右侧在连接／读取项目期间显示 Material 3 转圈，失败或待核对身份时显示警告三角形，正常时显示折叠箭头。展开同一主机可查看错误、最近同步时间，以及同步／重试和取消读取入口；独立“主机同步”区域已移除。没有项目缓存的主机也会出现在列表。SSH 连接和自动重连按主机记录进行状态，连接／项目读取错误单独保存，其他会话操作错误不会标为连接失败。

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过。
- 在 `emulator-5554` 上两项指定测试通过（`OK (2 tests)`）：`ProjectHomeTest#connectionProgressFailureAndRetryStayOnTheirHostRow` 和 `ProjectHomeTest#homeCollapsesHostsAndProjectsAndOpensConversationOnItsOwnHost`。
- 新测试用本机回环 TCP 端口暂停实际 SSH 握手，再主动关闭连接，验证零项目主机的转圈 → 警告、另一主机不显示转圈、在同一主机卡片重试、会话操作错误不显示连接警告，以及缓存项目／对话仍可展开。原有两层折叠与跨主机导航继续通过。
- 已检查浅色加载／失败截图，状态图标和主机文字、错误及重试按钮正常；截图和日志保存在忽略的 `.local/project-host-status/`。测试主机与缓存已清理，本轮未调用真实模型。
- vivo V2502A 解锁后已通过 USB 更新此版，安装返回 `Success`，打开 `MainActivity` 返回 `Status: ok`，已有配置保留。

## 项目页整合主机管理

2026-10-01：底部导航调整为项目／密钥／待处理，项目页顶部在“同步项目”左侧显示绿色“添加主机”。单击主机继续展开项目，长按打开底部操作菜单，提供进入工作台、连接、重连、编辑、克隆和删除。进入主机工作台可输入新项目路径；连接会同步项目，重连会关闭旧 SSH 与服务连接并重新建立。主机配置与凭据继续沿用现有存储。

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过。
- `emulator-5554` 上 `ProjectHomeTest`（4 项）、`HostCloneTest`（2 项）、`UiLayoutTest`（1 项）通过，结果 `OK (7 tests)`。
- 验证首页新增按钮位于同步左侧，新增／编辑保存、长按六项操作、进入工作台与返回项目、删除取消与确认、实际 SSH 等待／失败／重连，以及密码和密钥克隆。项目两层折叠、缓存会话、密钥与待处理导航继续可用。
- 已检查正常字号浅色项目首页与长按菜单、375 × 750dp 深色 2 倍字号、750 × 375dp 深色横屏截图，两个适配配置的 `UiLayoutTest` 均通过。顶部按钮保持添加在前、同步在后，大字号下换行；菜单直接完整展开，横屏可滚动访问全部六项操作。测试中的 LazyColumn 定位按列表查找目标项，验证展开后的项目／对话实际可见。模拟器配置已恢复，截图与日志保存在忽略的 `.local/project-host-management/`，本轮未使用真实模型。
- 已通过 USB 更新到 vivo V2502A，安装返回 `Success`，打开 `MainActivity` 返回 `Status: ok`，已有配置保留。
