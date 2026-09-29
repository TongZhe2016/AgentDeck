# Android 工程与 SSH 接入

2026-09-30，当前实现选择：沿用已初始化的 Compose 工程，以 SSHJ 0.40.0 实现 SSH。采用 Bouncy Castle 1.80 提供 Android 缺少的 Ed25519 算法；OkHttp 4.12.0 用于 SSH 隧道内的 HTTP。

参考候选已读取：

- Codex Remote Android：Apache-2.0，commit `372581ad5cc66677d5322a9a447a03277d36ce15`，其依赖表使用 SSHJ、Compose 和单应用模块。
- HAPI：AGPL-3.0，commit `86c88df93baf5d1f738dd4b202078bdf6dec376e`。

本次没有复制候选项目代码，也未编译这两个候选工程。当前已有原生工程可编译，SSHJ 在模拟器到 Mac 的密钥认证已成功，因此继续在现有工程实现；候选底座的完整比较与阶段 0 其他退出条件尚未完成。

主机和密钥元数据位于 `noBackupFilesDir`。私钥和密码单独用 Android Keystore AES-GCM 加密，同样位于不备份目录。首次 SSH 主机身份由用户确认，变化后必须重新核对。SSH 主机 SHA256 指纹用于这一信任决定，属于 SSH 原有身份语义。

来源：[SSHJ](https://github.com/hierynomus/sshj)、[Codex Remote Android](https://github.com/liuhao-labs/codex-remote-android)、[HAPI](https://github.com/tiann/hapi)。
