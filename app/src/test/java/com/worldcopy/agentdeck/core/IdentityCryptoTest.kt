package com.worldcopy.agentdeck.core

import com.worldcopy.agentdeck.core.ssh.IdentityCrypto
import com.worldcopy.agentdeck.core.ssh.SshKeyType
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.util.Base64

class IdentityCryptoTest {
    @Test fun generatedKeySurvivesStorageAndProducesOpenSshPublicKey() {
        for ((type, signatureAlgorithm, keyType) in listOf(
            Triple(SshKeyType.ED25519, "Ed25519", KeyType.ED25519),
            Triple(SshKeyType.RSA, "SHA256withRSA", KeyType.RSA),
            Triple(SshKeyType.ECDSA, "SHA256withECDSA", KeyType.ECDSA256),
        )) {
            val pair = IdentityCrypto.generate(type)
            if (type == SshKeyType.RSA) assertEquals(3072, (pair.public as java.security.interfaces.RSAPublicKey).modulus.bitLength())
            val restored = IdentityCrypto.restore(pair.private.encoded, pair.public.encoded, pair.private.algorithm)
            val line = IdentityCrypto.publicLine(restored).split(' ')
            assertEquals(keyType.toString(), line[0])
            val decoded = Buffer.PlainBuffer(Base64.getDecoder().decode(line[1])).readPublicKey()
            assertEquals(keyType, KeyType.fromKey(decoded))
            val message = "SSH authentication challenge".toByteArray()
            val signer = Signature.getInstance(signatureAlgorithm, "BC")
            signer.initSign(restored.private); signer.update(message)
            val signature = signer.sign()
            signer.initVerify(decoded); signer.update(message)
            assertTrue(signer.verify(signature))
            signer.initVerify(decoded); signer.update("other challenge".toByteArray())
            assertFalse(signer.verify(signature))
        }
    }
}
