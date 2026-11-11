package app.lensly.app.export

import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.utils.Numeric

/** The per-account export of the BIP39 test vector "abandon x23 art", whose account 0 is 0xF278cF59...1cdb. */
class AccountExportTest {

    private val artMnemonic = List(23) { "abandon" } + "art"
    private val artWallet by lazy { HdWallet(Mnemonic.toSeed(artMnemonic)) }

    @Test
    fun theCodeCarriesTheEip55AddressOfTheAccountAndNothingElse() {
        assertEquals(listOf("0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"), AccountExport.qrParts(artWallet.address(0)))
    }

    @Test
    fun theDerivationPathIsTheExternalChainOfTheAccount() {
        assertEquals("m/44'/60'/0'/0/0", AccountExport.derivationPath(0))
        assertEquals("m/44'/60'/0'/0/3", AccountExport.derivationPath(3))
    }

    @Test
    fun aNegativeIndexHasNoPath() {
        assertFailsWith<IllegalArgumentException> { AccountExport.derivationPath(-1) }
    }

    @Test
    fun theShownPathDerivesTheShownAddress() {
        // Derives each shown path with web3j, which is independent of the xpub code, and compares the address.
        val master = Bip32ECKeyPair.generateKeyPair(Mnemonic.toSeed(artMnemonic))
        for (index in 0..2) {
            val key = Bip32ECKeyPair.deriveKeyPair(master, pathComponents(AccountExport.derivationPath(index)))
            val privateKey = Numeric.toBytesPadded(key.privateKey, PRIVATE_KEY_BYTES)
            try {
                assertEquals(
                    AccountAddresses.addressOfPrivateKey(privateKey),
                    AccountExport.qrParts(artWallet.address(index)).single(),
                    "index $index",
                )
            } finally {
                privateKey.fill(0)
            }
        }
    }

    /** The components of a path such as "m/44'/60'/0'/0/3", with a hardened step as the BIP32 hardened number. */
    private fun pathComponents(path: String): IntArray = path.removePrefix("m/").split("/").map { step ->
        if (step.endsWith("'")) step.dropLast(1).toInt() or Bip32ECKeyPair.HARDENED_BIT else step.toInt()
    }.toIntArray()

    private companion object {
        const val PRIVATE_KEY_BYTES = 32
    }
}
