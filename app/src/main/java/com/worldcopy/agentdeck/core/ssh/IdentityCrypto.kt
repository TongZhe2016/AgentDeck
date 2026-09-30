package com.worldcopy.agentdeck.core.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object IdentityCrypto {
    // Android's bundled BC provider lacks Ed25519; SSHJ needs the complete provider.
    @Synchronized
    fun initialize() {
        if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
        SecurityUtils.setSecurityProvider("BC")
    }

    fun generate(): KeyPair {
        initialize()
        return KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
    }

    fun restore(privateBytes: ByteArray, publicBytes: ByteArray, algorithm: String = "Ed25519"): KeyPair {
        initialize()
        val factory = KeyFactory.getInstance(algorithm, "BC")
        return KeyPair(factory.generatePublic(X509EncodedKeySpec(publicBytes)),
            factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
    }

    fun publicLine(pair: KeyPair): String =
        "${KeyType.fromKey(pair.public)} ${Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(pair.public).compactData)} agentdeck"

    fun importKey(privateText: String, publicText: String = "", passphrase: String = ""): KeyPair {
        initialize()
        val source = privateText.trim()
        require(source.isNotEmpty()) { "请选择私钥文件或粘贴私钥内容" }
        require(source.startsWith("-----BEGIN ") && source.lineSequence().first().contains("PRIVATE KEY-----")) {
            "请选择 OpenSSH 或 PEM 私钥文件；.pub 公钥不能单独用于登录"
        }
        val password = passphrase.toCharArray()
        val pair = try {
            if (source.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) SSHClient().use { ssh ->
                val provider = ssh.loadKeys(source, null, PasswordUtils.createOneOff(password))
                KeyPair(provider.public, provider.private)
            } else readPem(source, password)
        } catch (_: Exception) {
            // Parser errors may include input text. Keep credential contents out of UI/logs.
            throw IllegalArgumentException("无法读取私钥，请检查文件格式和私钥口令（不是电脑登录密码）")
        } finally { password.fill('\u0000') }
        require(KeyType.fromKey(pair.public) in listOf(KeyType.ED25519, KeyType.RSA, KeyType.ECDSA256, KeyType.ECDSA384, KeyType.ECDSA521)) {
            "目前支持 Ed25519、RSA 和 ECDSA（NIST P-256/P-384/P-521）私钥"
        }
        if (publicText.isNotBlank()) {
            val supplied = publicText.trim().split(Regex("\\s+"))
            val expected = publicLine(pair).split(' ')
            require(supplied.size >= 2 && supplied[0] == expected[0] && supplied[1] == expected[1]) {
                "公钥与私钥不匹配，请选择对应的 .pub 文件，或留空自动提取公钥"
            }
        }
        return pair
    }

    private fun readPem(source: String, password: CharArray): KeyPair {
        val parsed = PEMParser(source.reader()).use { it.readObject() }
        val converter = JcaPEMKeyConverter().setProvider("BC")
        val key = when (parsed) {
            is PEMEncryptedKeyPair -> parsed.decryptKeyPair(JcePEMDecryptorProviderBuilder().setProvider("BC").build(password))
            is PKCS8EncryptedPrivateKeyInfo -> parsed.decryptPrivateKeyInfo(JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider("BC").build(password))
            else -> parsed
        }
        if (key is PEMKeyPair) return converter.getKeyPair(key)
        require(key is PrivateKeyInfo) { "需要 PEM 私钥" }
        // PKCS#8 may only contain the private component; derive its public component.
        val publicParameters = when (val privateParameters = PrivateKeyFactory.createKey(key)) {
            is Ed25519PrivateKeyParameters -> privateParameters.generatePublicKey()
            is RSAPrivateCrtKeyParameters -> RSAKeyParameters(false, privateParameters.modulus, privateParameters.publicExponent)
            is ECPrivateKeyParameters -> ECPublicKeyParameters(privateParameters.parameters.g.multiply(privateParameters.d), privateParameters.parameters)
            else -> error("不支持此私钥算法")
        }
        return KeyPair(converter.getPublicKey(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicParameters)), converter.getPrivateKey(key))
    }
}
