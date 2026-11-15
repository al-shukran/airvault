package app.lensly.core.registry

import app.lensly.ui.shippedRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class WalletEntryTest {
    @Test
    fun theShippedRegistryNamesTheWalletsWithTheirCodeFormats() {
        val wallets = shippedRegistry().walletEntries().associateBy { it.name }

        assertEquals(setOf("Rabby", "MetaMask", "Keystone Nexus"), wallets.keys)
        assertTrue(wallets.values.all { "ur:eth-sign-request" in it.formats && it.limitations.isNotBlank() })
        assertTrue("ur:crypto-multi-accounts" in wallets.getValue("Keystone Nexus").formats)
    }

    @Test
    fun aWalletNeedsANoteLimitationsAndACodeFormat() {
        assertFailsWith<IllegalArgumentException> {
            WalletEntry("Blank note", listOf("EVM"), listOf("ur:eth-sign-request"), " ", "Limits.")
        }
        assertFailsWith<IllegalArgumentException> {
            WalletEntry("No format", listOf("EVM"), emptyList(), "Note.", "Limits.")
        }
        assertFailsWith<IllegalArgumentException> {
            WalletEntry("Spoofed‮", listOf("EVM"), listOf("ur:eth-sign-request"), "Note.", "Limits.")
        }
    }

    @Test
    fun aWalletNameAppearsOnceInTheRegistry() {
        val rabby = shippedRegistry().walletEntries().single { it.name == "Rabby" }

        assertFailsWith<IllegalArgumentException> { Registry(emptyList(), emptyList(), wallets = listOf(rabby, rabby)) }
        assertEquals(listOf(rabby), Registry(emptyList(), emptyList(), wallets = listOf(rabby)).walletEntries())
    }
}
