package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.MainActivity
import com.worldcopy.agentdeck.core.storage.HostStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.Signature

class KeyImportScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as AgentDeckApplication
    private val name = "导入测试密钥"
    private fun fixture(file: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("keys/$file").bufferedReader().use { it.readText() }

    @After fun cleanup() {
        compose.waitUntil(5000) { !app.hosts.busy }
        app.hosts.identities.filter { it.name == name }.forEach { key ->
            compose.runOnUiThread { app.hosts.deleteKey(key) }
            compose.waitUntil(5000) { !app.hosts.busy && app.hosts.identities.none { it.id == key.id } }
        }
    }

    @Test fun importDialogRetainsInputAfterMismatchAndStoresEncryptedKey() {
        compose.onNodeWithText("密钥", useUnmergedTree = true).performClick()
        compose.onNodeWithText("导入已有密钥").performScrollTo().performClick()
        compose.onNodeWithText("密钥名称").performTextInput(name)
        compose.onNodeWithTag("import-private").performScrollTo().performTextInput(fixture("ed25519-encrypted"))
        compose.onNodeWithTag("import-passphrase").performScrollTo().performTextInput("fixture-passphrase")
        compose.onNodeWithTag("import-public").performScrollTo().performTextInput(fixture("rsa.pub"))
        compose.onNodeWithText("导入", substring = false).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("公钥与私钥不匹配", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.hosts.identities.none { it.name == name })
        compose.onNodeWithTag("import-public").performScrollTo().performTextClearance()
        compose.onNodeWithText("导入", substring = false).performClick()
        compose.waitUntil(15000) { app.hosts.identities.any { it.name == name } }
        compose.onNodeWithTag("import-private").assertDoesNotExist()
        compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        val identity = app.hosts.identities.first { it.name == name }
        val restored = HostStore(app).keyPair(identity.id)
        val encrypted = File(app.noBackupFilesDir, "credentials/key-${identity.id}").readBytes()
        assertFalse(encrypted.contentEquals(restored.private.encoded))
        assertEquals(fixture("ed25519-encrypted.pub").split(' ').take(2), identity.publicKey.split(' ').take(2))
    }

    @Test fun importedAlgorithmsSignAfterEncryptedStorageRoundTrip() {
        val store = HostStore(app)
        for ((file, algorithm) in listOf("ed25519" to "Ed25519", "ed25519-encrypted" to "Ed25519",
            "rsa" to "SHA256withRSA", "rsa-pem-encrypted" to "SHA256withRSA",
            "ecdsa" to "SHA256withECDSA", "ecdsa-pem" to "SHA256withECDSA")) {
            val identity = store.importIdentity("Storage import test", fixture(file), fixture("$file.pub"),
                if (file.endsWith("encrypted")) "fixture-passphrase" else "")
            try {
                val restored = HostStore(app).keyPair(identity.id)
                val signature = Signature.getInstance(algorithm, "BC")
                val challenge = "SSH test challenge".toByteArray()
                signature.initSign(restored.private); signature.update(challenge)
                val signed = signature.sign()
                signature.initVerify(restored.public); signature.update(challenge)
                assertTrue(file, signature.verify(signed))
            } finally { store.deleteIdentity(identity.id) }
        }
    }
}
