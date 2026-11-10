package app.lensly.ui.flow

import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.export.PairingExport
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

private val WALLET by lazy { HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art")) }

class PairPartsTest {

    private fun wallet(name: String, fingerprintHex: String = WALLET.masterFingerprintHex) = WalletMeta(
        id = "00000000-0000-4000-8000-000000000000",
        name = name,
        createdAtMs = 0L,
        deviceGenerated = false,
        accountCount = 1,
        masterFingerprintHex = fingerprintHex,
        accountXpub = WALLET.accountXpub(),
    )

    @Test
    fun thePartsAreNamedAfterTheWalletAndCarryItsFingerprint() {
        assertEquals(
            PairingExport.urParts(WALLET.accountXpub(), WALLET.masterFingerprint, "Savings"),
            pairingParts(wallet("Savings")),
        )
    }

    @Test
    fun aBlankWalletNameFallsBackToLensly() {
        assertEquals(
            PairingExport.urParts(WALLET.accountXpub(), WALLET.masterFingerprint, "Lensly"),
            pairingParts(wallet("   ")),
        )
    }

    @Test
    fun aFingerprintWithItsTopBitSetIsReadAsUnsigned() {
        assertEquals(
            PairingExport.urParts(WALLET.accountXpub(), -1, "Lensly"),
            pairingParts(wallet("Lensly", fingerprintHex = "ffffffff")),
        )
    }

    @Test
    fun aSingleKeyHasNoAccountKeyToPair() {
        // PAIR WALLET shows an explanation for a Single Key and never builds these parts, so asking for them is a bug.
        val singleKey = WalletMeta(
            id = "00000000-0000-4000-8000-000000000000",
            name = "Imported",
            createdAtMs = 0L,
            deviceGenerated = false,
            accountCount = 1,
            masterFingerprintHex = null,
            accountXpub = null,
            kind = WalletKind.SINGLE_KEY,
        )
        assertFailsWith<IllegalArgumentException> { pairingParts(singleKey) }
    }
}
