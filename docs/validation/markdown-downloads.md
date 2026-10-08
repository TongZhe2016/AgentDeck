# Markdown 与文件下载

2026-10-08，Android 与电脑服务共同更新。

## 使用行为

- 正文通过 CommonMark AST 渲染为原生 Compose：六级标题、Setext 标题、粗体、斜体、行内代码、删除线、有序／无序及嵌套列表、任务列表、引用、分隔线、表格、缩进／围栏代码、链接、引用式链接和自动 URL 识别。
- 表格保留列对齐与表头样式，宽表格横向滚动。长代码行横向滚动，保留缩进并提供复制。
- 点击本机文件引用后显示路径和下载按钮，通过 Android 创建文档选择器选择保存位置。支持会话项目相对路径、绝对路径、file URI、sandbox 路径及 `:12`／`:12:4`／`#L12-L18` 行号后缀。路径中的中文、空格和加号正常保留。
- 下载经当前主机 SSH 隧道、服务令牌认证和流式 HTTP 传输，手机直接写入用户选择的文档。取消或失败时尝试删除未完成文档；服务仅允许普通文件，目录需先自行打包。网页／邮件链接交给对应应用打开。
- 图片语法作为可点击引用，可下载主机文件或打开网页图片。原始 HTML 保留为文本（行内 br 支持换行）；数学公式、Mermaid 与文内锚点跳转尚未专门渲染。

## 验证

- Android debug、设备测试 APK、lint 通过；2 项 JVM 测试覆盖链接路径解析、GFM 表格、嵌套格式、引用式链接、有序列表起始序号及流式未闭合代码围栏。
- API 36 模拟器通过表格横向滚动到最后一列、任务标记、点击文件链接及代码复制。检查 375dp 内容宽度浅色与 750dp 内容宽度深色（2 倍字体、关闭动画）的截图；宽表格在窄内容区可滚动。
- 设备下载测试通过：点击消息引用，触发 ACTION_CREATE_DOCUMENT 并返回选定 content URI，再通过 HTTP 保存 1 MiB 二进制内容，逐字节一致；选择器结果由 instrumentation 提供，未自动操作系统文件浏览器。另验证直接流式保存、中文路径查询以及失败响应不会写入文件内容。
- 原有 ChatScrollTest 通过：最新长段落末行、流式追加、向上翻阅保持位置及离线缓存继续直达末尾。测试改为检查真实文字末行坐标，以适配整段 Markdown 渲染。
- 电脑服务全部 12 项测试通过，新增用例覆盖认证、绝对／相对路径、中文文件名、2 MiB 二进制字节一致、空文件、缺失文件及目录拒绝。
- MacBook 与五台 Ubuntu 已更新；每台真实下载 64 KiB 测试文件并逐字节比对通过，见 [部署记录](server-deployment.md)。

截图、测试日志和部署脚本在忽略的 `.local/markdown-download/`。新版 APK 已在模拟器安装；真机仍等待此前被拒的 USB 安装确认。本轮未调用收费模型。

## 上游依赖

使用 Maven 依赖，不复制上游渲染代码。新依赖声明在版本目录，许可证随 APK 放入 `assets/licenses/`。

| 依赖 | 来源与固定版本 | 对应提交 | 许可证 |
| --- | --- | --- | --- |
| commonmark-java 与表格／删除线／自动链接／任务列表扩展 | [commonmark-java 0.27.1](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.27.1) | `cded1b17fd6c557e7bd162b8cbe46e3e21f2adfe` | BSD-2-Clause，保留 Robin Stocker 的版权与完整条款 |
| autolink-java（自动链接扩展的传递依赖） | [autolink-java 0.12.0](https://github.com/robinst/autolink-java/tree/autolink-0.12.0) | `8b07a6911fd62871042c5b63412d3ac3489a0531` | MIT，保留版权与完整条款 |
