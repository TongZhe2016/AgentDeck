package com.worldcopy.agentdeck.core

import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.ssh.SshConnection
import com.worldcopy.agentdeck.core.storage.HostStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CredentialIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedIdentityRoundTripAndDeletion() {
        val store = HostStore(context)
        val identity = store.createIdentity("Storage test")
        val host = Host(name = "Storage test", address = "localhost", username = "test", identityId = identity.id)
        try {
            store.saveHost(host)
            val restored = HostStore(context)
            assertEquals(identity.publicKey, restored.identities().first { it.id == identity.id }.publicKey)
            assertNotNull(restored.keyPair(identity.id))
            val encrypted = File(context.noBackupFilesDir, "credentials/key-${identity.id}").readBytes()
            assertFalse(encrypted.contentEquals(restored.keyPair(identity.id).private.encoded))
            restored.deleteIdentity(identity.id)
            assertNull(HostStore(context).hosts().first { it.id == host.id }.identityId)
            assertFalse(File(context.noBackupFilesDir, "credentials/key-${identity.id}").exists())
        } finally {
            store.deleteHost(host.id)
            store.deleteIdentity(identity.id)
        }
    }

    // Run prepare, authorize the exported public key on the test host, then run connect.
    @Test fun macSshIntegration() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("sshPhase") ?: return
        val store = HostStore(context)
        if (phase == "prepare") {
            val identity = store.createIdentity("Mac integration")
            File(context.cacheDir, "integration.pub").writeText(identity.publicKey + "\n")
            File(context.cacheDir, "integration-id").writeText(identity.id)
        } else {
            val id = File(context.cacheDir, "integration-id").readText()
            val host = Host(name = "Mac integration", address = "10.0.2.2", username = args.getString("sshUser")!!,
                identityId = id, trustedHostKey = args.getString("sshHostKey"))
            SshConnection.connect(host, null, store.keyPair(id)).use { assertTrue(it.connected) }
        }
    }
}
