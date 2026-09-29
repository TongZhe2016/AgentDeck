package com.worldcopy.agentdeck.core.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
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

    fun restore(privateBytes: ByteArray, publicBytes: ByteArray): KeyPair {
        initialize()
        val factory = KeyFactory.getInstance("Ed25519", "BC")
        return KeyPair(factory.generatePublic(X509EncodedKeySpec(publicBytes)),
            factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
    }

    fun publicLine(pair: KeyPair): String =
        "ssh-ed25519 ${Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(pair.public).compactData)} agentdeck"
}
