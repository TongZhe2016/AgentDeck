package com.worldcopy.agentdeck.core.storage

import android.content.Context
import android.util.AtomicFile
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.model.SshIdentity
import com.worldcopy.agentdeck.core.ssh.IdentityCrypto
import com.worldcopy.agentdeck.core.ssh.SshKeyType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.util.Base64
import java.util.UUID

class HostStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "hosts.json"))
    val vault = CredentialVault(context)
    private var data = if (file.baseFile.exists()) JSONObject(String(file.readFully())) else JSONObject()

    @Synchronized
    fun hosts(): List<Host> = objects("hosts").map {
        Host(it.getString("id"), it.getString("name"), it.getString("address"), it.getInt("port"),
            it.getString("username"), AuthMethod.valueOf(it.getString("authMethod")),
            it.optString("identityId").ifBlank { null }, it.optString("trustedHostKey").ifBlank { null },
            it.optInt("servicePort", 4317), it.optString("serviceDirectory").ifBlank { "~/.agentdeck" })
    }

    @Synchronized
    fun identities(): List<SshIdentity> = objects("identities").map {
        SshIdentity(it.getString("id"), it.getString("name"), it.getString("publicKey"))
    }

    @Synchronized
    fun saveHost(host: Host, password: String? = null, token: String? = null) {
        host.validate()
        password?.let { vault.put("password-${host.id}", it.toByteArray()) }
        token?.let { vault.put("token-${host.id}", it.toByteArray()) }
        val obj = JSONObject().put("id", host.id).put("name", host.name).put("address", host.address)
            .put("port", host.port).put("username", host.username).put("authMethod", host.authMethod.name)
            .put("identityId", host.identityId ?: "").put("trustedHostKey", host.trustedHostKey ?: "")
            .put("servicePort", host.servicePort).put("serviceDirectory", host.serviceDirectory)
        replace("hosts", host.id, obj)
    }

    @Synchronized
    fun deleteHost(id: String) {
        replace("hosts", id, null)
        vault.delete("password-$id")
        vault.delete("token-$id")
    }

    @Synchronized
    fun createIdentity(name: String, type: SshKeyType = SshKeyType.ED25519): SshIdentity {
        require(name.isNotBlank()) { "请填写密钥名称" }
        return saveIdentity(name, IdentityCrypto.generate(type))
    }

    @Synchronized
    fun importIdentity(name: String, privateText: String, publicText: String = "", passphrase: String = ""): SshIdentity {
        require(name.isNotBlank()) { "请填写密钥名称" }
        return saveIdentity(name, IdentityCrypto.importKey(privateText, publicText, passphrase))
    }

    private fun saveIdentity(name: String, pair: KeyPair): SshIdentity {
        val id = UUID.randomUUID().toString()
        vault.put("key-$id", pair.private.encoded)
        val identity = SshIdentity(id, name.trim(), IdentityCrypto.publicLine(pair))
        replace("identities", id, JSONObject().put("id", id).put("name", identity.name)
            .put("publicKey", identity.publicKey).put("algorithm", pair.private.algorithm)
            .put("encodedPublic", Base64.getEncoder().encodeToString(pair.public.encoded)))
        return identity
    }

    @Synchronized
    fun keyPair(id: String): KeyPair {
        val record = objects("identities").firstOrNull { it.getString("id") == id }
            ?: error("密钥已删除，请重新选择")
        return IdentityCrypto.restore(vault.get("key-$id") ?: error("私钥不可用，请创建新密钥"),
            Base64.getDecoder().decode(record.getString("encodedPublic")), record.optString("algorithm", "Ed25519"))
    }

    @Synchronized
    fun renameIdentity(id: String, name: String) {
        require(name.isNotBlank()) { "请填写密钥名称" }
        replace("identities", id, objects("identities").first { it.getString("id") == id }.put("name", name.trim()))
    }

    @Synchronized
    fun deleteIdentity(id: String) {
        // Update metadata in one write, then remove encrypted material.
        val updated = JSONObject(data.toString())
        updated.put("identities", JSONArray(objects("identities").filter { it.getString("id") != id }))
        updated.put("hosts", JSONArray(objects("hosts").map {
            if (it.optString("identityId") == id) it.put("identityId", "") else it
        }))
        persist(updated)
        vault.delete("key-$id")
    }

    private fun objects(name: String): List<JSONObject> {
        val array = data.optJSONArray(name) ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun replace(name: String, id: String, record: JSONObject?) {
        val records = objects(name).filter { it.getString("id") != id } + listOfNotNull(record)
        persist(JSONObject(data.toString()).put(name, JSONArray(records)))
    }

    private fun persist(updated: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(updated.toString().toByteArray()); file.finishWrite(stream); data = updated }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
