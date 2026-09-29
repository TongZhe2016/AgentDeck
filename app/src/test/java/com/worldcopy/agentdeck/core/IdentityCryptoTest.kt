package com.worldcopy.agentdeck.core

import com.worldcopy.agentdeck.core.ssh.IdentityCrypto
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.util.Base64

class IdentityCryptoTest {
    @Test fun generatedKeySurvivesStorageAndProducesOpenSshPublicKey() {
        val pair = IdentityCrypto.generate()
        val restored = IdentityCrypto.restore(pair.private.encoded, pair.public.encoded)
        val line = IdentityCrypto.publicLine(restored).split(' ')
        assertEquals("ssh-ed25519", line[0])
        val decoded = Buffer.PlainBuffer(Base64.getDecoder().decode(line[1])).readPublicKey()
        assertEquals(KeyType.ED25519, KeyType.fromKey(decoded))
        val message = "SSH authentication challenge".toByteArray()
        val signer = Signature.getInstance("Ed25519", "BC")
        signer.initSign(restored.private); signer.update(message)
        val signature = signer.sign()
        signer.initVerify(decoded); signer.update(message)
        assertTrue(signer.verify(signature))
        signer.initVerify(decoded); signer.update("other challenge".toByteArray())
        assertFalse(signer.verify(signature))
    }
}
