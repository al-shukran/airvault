package app.lumio.pair

import app.lensly.app.addressKeyOf
import app.lensly.app.export.LabelledPairingGolden
import app.lensly.core.ur.UrScanner
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The wallets that Lumio 1.1.0 stored stay readable after an update, so the owner does not pair again, and a code from Lensly 1.3.0 still pairs.
 * The records below are the bytes that the writer of the lumio-v1.1.0 tag produced for a wallet "Demo" with two Ethereum accounts and one Solana account.
 */
class PairedWalletsV1_1RecordsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun aWalletThatLumio1_1Wrote_loadsAndNamesItsAccountsAsBefore() {
        val id = session.createWallet()
        session.putRecords(
            id,
            mapOf(
                WalletRecordNames.META to hex(META_1_1),
                WalletRecordNames.account(0) to hex(ACCOUNT_0_1_1),
                WalletRecordNames.account(1) to hex(ACCOUNT_1_1_1),
                WalletRecordNames.SOLANA to hex(SOLANA_1_1),
            ),
            emptyList(),
        )

        val wallet = PairedWallets.list(session).single()
        val own = ownAccountsOf(listOf(wallet))

        assertEquals("Demo", wallet.name)
        assertEquals("Demo", wallet.autoNameBase)
        assertEquals(listOf("Savings", null), wallet.evmAccounts.map { it.name })
        assertEquals(listOf("Demo 0", "Demo 1"), wallet.evmAccounts.map { own[addressKeyOf(it.address)]?.autoName })
        assertEquals(1, wallet.solana.size)
        assertNull(wallet.solanaNameAt(0))
    }

    @Test
    fun aCodeFromLensly1_3_0KeepsItsAutoNameBase() {
        val scanner = UrScanner()
        LabelledPairingGolden.FRAMES.forEach { scanner.receive(it) }

        PairedWallets.save(session, PairImport.of(requireNotNull(scanner.result())), nowMs = 1L)
        val wallet = PairedWallets.list(session).single()

        assertEquals("Savings", wallet.autoNameBase)
        assertEquals(listOf("Cold", "Savings 1"), wallet.evmAccounts.map { it.name })
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val META_1_1 = "04123456780000018bcfe568000444656d6f02307846323738634635394638326544636638373164363330463238456343383035366632354331" +
            "636462307839343134324234663636353331364433333034433361353935656338336143394338303436353938"
        const val ACCOUNT_0_1_1 = "0407536176696e677304636f6c64094c6f6e67207465726d"
        const val ACCOUNT_1_1_1 = "04000000"
        const val SOLANA_1_1 = "01010102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    }
}
