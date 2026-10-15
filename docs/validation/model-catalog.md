# 宿主机模型 Catalog（2026-10-16）

模型列表来自宿主机运行中的 Codex 原生 `model/list`，服务以 `includeHidden:true` 读取所有分页。App 每次打开执行设置时读取，也提供“刷新模型”。显示名称、实际模型 ID 和思考档位全部来自宿主机，可包含 DeepSeek 或其他自定义名称。

无可调思考档位的模型显示说明，关闭设置时仍可保存。服务使用该模型在 Catalog 中的默认档位，例如 `none`；不传空值以免 Codex 沿用上一模型的档位。新建、恢复和 Fork 返回的设置也按此规则处理。刷新期间暂停编辑；所选模型被移出新目录时提示重新选择，并允许放弃未保存更改。

自定义 Catalog 的启用、服务重启及 provider 路由说明见 [电脑服务指南](../../host-service/README.md#自定义模型-catalog)。本地 Catalog 在 Codex 启动时加载，手机刷新查询当前运行实例，不重新加载本地 Catalog 文件。

验证结果：

- `npm --prefix host-service test`：19 项通过。覆盖完整目录分页、隐藏模型、未知型号、刷新后的新型号、固定档位默认值、新建与恢复设置、执行请求，以及已有模型的档位校验。
- Android debug APK、设备测试 APK、单元测试和 lint 通过；lint 为 0 errors、36 项现有 warnings。
- API 36 ARM64 模拟器 `emulator-5554`：模型 Catalog 两项、现有执行设置三项和历史续聊四项共九项设备测试通过。最终默认档位处理调整后，另跑 Catalog 两项通过，确认无档位模型保存为宿主机默认 `none`、刷新实际查询电脑并发现新型号、目录删除当前选择后可放弃更改。
- 约 376dp 浅色竖屏、两倍字体深色横屏（关闭动画）下，Catalog 两项测试均通过；已查看截图，型号 ID、说明和操作可阅读、可滚动访问。
- 真实 Codex 0.161.0 + 服务 HTTP 接口：隔离 `CODEX_HOME`，加载含 `catalog-reasoner`、`catalog-chat`、`catalog-hidden` 的原生自定义 Catalog。接口返回全部三项；选择无可调档位的模型后，本地合成 Responses 端点收到正确模型 ID 和 `none` 档位，完整执行并读回 `CATALOG_OK`。未调用收费模型。

日志、原生合成配置和截图位于忽略的 `.local/model-catalog/`。本次未改动日常 Codex 全局配置、未部署日常电脑服务，物理手机和真实第三方供应商调用尚未验证。
