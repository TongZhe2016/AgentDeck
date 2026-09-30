# Android 界面验证

2026-10-01：Material 3 品牌主题、项目／主机／密钥／待处理页、聊天输入区与全屏 Diff。

## 已完成

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 通过。
- Android 模拟器 375 × 750dp、浅色、系统字号：`UiLayoutTest` 通过项目展开、进入历史、编辑草稿、附件菜单、打开／关闭 Diff、主机编辑入口、密钥导入入口及待处理导航。
- `ProjectHomeTest` 两项、`CommitGraphTest` 一项、`KeyImportScreenTest` 两项通过，覆盖跨主机分组、提交展开和实际密钥导入行为。
- 品牌配色正文、次级正文、primary 和 primaryContainer 前景／背景的浅深色对比度均超过 4.5:1。

布局测试只在模拟器写入合成主机、会话和 Git 缓存，结束后清理。截图与构建日志留在忽略的 `.local/ui-refresh/`。本次未调用真实模型 API；SSH 和电脑服务未变更。

深色、大字号和宽屏适配以及物理手机安装记录将在完成后补充。
