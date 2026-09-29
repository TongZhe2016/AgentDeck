package com.worldcopy.agentdeck.core.model

import java.util.UUID

enum class AuthMethod { PASSWORD, KEY }

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.KEY,
    val identityId: String? = null,
    val trustedHostKey: String? = null,
    val servicePort: Int = 4317,
) {
    fun validate() {
        require(name.isNotBlank()) { "请填写主机名称" }
        require(address.isNotBlank()) { "请填写 SSH 地址" }
        require(username.isNotBlank()) { "请填写用户名" }
        require(port in 1..65535 && servicePort in 1..65535) { "端口必须在 1–65535 之间" }
        require(authMethod != AuthMethod.KEY || identityId != null) { "请选择登录密钥" }
    }
}

data class SshIdentity(val id: String, val name: String, val publicKey: String)
