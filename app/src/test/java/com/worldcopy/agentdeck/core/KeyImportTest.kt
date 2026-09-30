package com.worldcopy.agentdeck.core

import com.worldcopy.agentdeck.core.ssh.IdentityCrypto
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature

class KeyImportTest {
    private fun fixture(name: String) = javaClass.getResource("/keys/$name")!!.readText()

    @Test fun importsOpenSshAndPemKeysAndRestoresSigning() {
        for ((name, signatureAlgorithm) in listOf(
            "ed25519" to "Ed25519", "ed25519-encrypted" to "Ed25519",
            "rsa" to "SHA256withRSA", "rsa-pem-encrypted" to "SHA256withRSA",
            "ecdsa" to "SHA256withECDSA", "ecdsa-pem" to "SHA256withECDSA",
        )) {
            val password = if (name.endsWith("encrypted")) "fixture-passphrase" else ""
            val pair = try { IdentityCrypto.importKey(fixture(name), fixture("$name.pub"), password) }
            catch (e: Exception) { throw AssertionError("Import fixture: $name", e) }
            val restored = IdentityCrypto.restore(pair.private.encoded, pair.public.encoded, pair.private.algorithm)
            assertEquals(fixture("$name.pub").split(' ').take(2), IdentityCrypto.publicLine(restored).split(' ').take(2))
            val signer = Signature.getInstance(signatureAlgorithm, "BC")
            val challenge = "imported SSH credential".toByteArray()
            signer.initSign(restored.private); signer.update(challenge)
            val signature = signer.sign()
            signer.initVerify(restored.public); signer.update(challenge)
            assertTrue(name, signer.verify(signature))
        }
        val extracted = IdentityCrypto.importKey(fixture("ed25519"))
        assertEquals(fixture("ed25519.pub").split(' ').take(2), IdentityCrypto.publicLine(extracted).split(' ').take(2))
    }

    @Test fun derivesPublicKeysFromPkcs8PrivateComponents() {
        for (name in listOf("ed25519", "rsa", "ecdsa")) {
            val original = IdentityCrypto.importKey(fixture(name))
            val body = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(original.private.encoded)
            val imported = IdentityCrypto.importKey("-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----")
            assertEquals(IdentityCrypto.publicLine(original), IdentityCrypto.publicLine(imported))
        }
    }

    @Test fun rejectsIncorrectPassphraseMismatchedPublicKeyAndPublicOnlyInput() {
        for (password in listOf("", "wrong")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                IdentityCrypto.importKey(fixture("ed25519-encrypted"), passphrase = password)
            }
            assertTrue(failure.message!!.contains("口令"))
        }
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            IdentityCrypto.importKey(fixture("ed25519"), fixture("rsa.pub"))
        }.message!!.contains("不匹配"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            IdentityCrypto.importKey(fixture("ed25519.pub"))
        }.message!!.contains("不能单独用于登录"))
        assertThrows(IllegalArgumentException::class.java) {
            IdentityCrypto.importKey("-----BEGIN OPENSSH PRIVATE KEY-----\nbroken\n-----END OPENSSH PRIVATE KEY-----")
        }
    }
}
