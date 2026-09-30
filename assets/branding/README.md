# AgentDeck 标识

“掌舵”与终端控制结合：八向舵柄、圆环和 `>_` 光标。顶部绿色舵柄标记方向，图形内不加入文字，便于小尺寸识别。

- 深海蓝 `#15243B`：图标背景。
- 薄荷绿 `#56E0C2`：终端符号与方向标记。
- 浅白 `#F2F6FB`：舵轮。

`agentdeck-mark.svg` 是透明底标志，`agentdeck-icon.svg` 和 `agentdeck-icon-round.svg` 是圆角方形／圆形版本。`agentdeck-monochrome.svg` 为单色源图；`agentdeck-icon-1024.png` 是 1024px 导出；`preview.svg`／`preview.png` 展示配色、桌面形状和尺寸。

Android 使用 `app/src/main/res/drawable/ic_launcher_foreground.xml`、背景及独立 monochrome 图层；两个 adaptive-icon 入口已引用新资源，旧密度目录的 WebP 也已更新。前景使用 108×108 坐标，圆心为 (54,54)，最外轮廓半径 30.25，位于中心安全区内。背景交由桌面启动器裁剪。主题图标使用单色图层，由系统着色。

这些素材以矢量路径直接绘制。修改标志时同步 SVG 与 Android VectorDrawable 的路径；SVG 可通过矢量编辑器或 SVG 渲染工具导出 PNG／WebP，不影响常规 Gradle 构建。
