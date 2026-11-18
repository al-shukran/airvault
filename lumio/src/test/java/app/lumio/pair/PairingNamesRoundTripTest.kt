package app.lumio.pair

import app.lensly.app.autoAccountName
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.Cbor
import app.lensly.core.wallet.HdWallet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A wallet named "Air" with ten accounts in each App. Every account is named by its Auto-Name except Ethereum account 2, which is named
 * "PolarNova". The Bitcoin App holds the ten mainnet keys and then the ten testnet4 keys, as Lensly's Bitcoin App lists them. The body
 * carries the wallet name, the Auto-Name base and the one custom name, and Lumio reads back the Auto-Name of every other account.
 */
class PairingNamesRoundTripTest {

    @Test
    fun theEncodedBodyCarriesTheWalletNameTheAutoNameBaseAndTheCustomNameOnly() {
        val envelope = PairingCodec.encode(scenario(), brotli = null)

        val texts = textsOf(bodyOf(envelope))

        // No "Air 0" to "Air 9": an Auto-Named account is sent with an empty name, so the body has no text for it.
        assertEquals(
            listOf("Air", "Air", "PolarNova", "algorand", "bitcoin", "ethereum", "solana"),
            texts.filter { it.isNotEmpty() }.sorted(),
        )
    }

    @Test
    fun lumioReadsEveryAccountWithItsAutoNameExceptTheCustomOne() {
        val read = pairingOf(PairingCodec.urParts(scenario(), brotli = null))

        assertEquals(BASE, read.name)
        assertEquals(BASE, read.autoNameBase)
        assertEquals(
            (0 until ACCOUNTS).map { if (it == CUSTOM_INDEX) CUSTOM_NAME else autoAccountName(BASE, it) },
            read.apps.single { it.app == PairImport.ETHEREUM }.accounts.map { it.name },
        )
        val autoNames = (0 until ACCOUNTS).map { autoAccountName(BASE, it) }
        assertEquals(autoNames + autoNames, read.apps.single { it.app == PairImport.BITCOIN }.accounts.map { it.name })
        assertEquals(autoNames, read.apps.single { it.app == PairImport.SOLANA }.accounts.map { it.name })
        assertEquals(autoNames, read.apps.single { it.app == PairImport.ALGORAND }.accounts.map { it.name })
    }

    /** The pairing of the scenario, as Lensly's Apps build it: each account carries its name, and its Auto-Name when it has none. */
    private fun scenario(): PairingPayload {
        val seed = PairFixtures.seed
        val indexes = 0 until ACCOUNTS
        val ethereum = PairingApp(
            app = PairImport.ETHEREUM,
            keys = indexes.map { PairingKey.ethereumAddress(it, PairFixtures.address(seed, it)) },
            accounts = indexes.map { index ->
                val name = if (index == CUSTOM_INDEX) CUSTOM_NAME else autoAccountName(BASE, index)
                PairingAccount(key = index, name = name, label = null, group = null)
            },
        )
        val bitcoinAccounts = listOf(BtcNetwork.MAINNET, BtcNetwork.TESTNET4).flatMap { network ->
            indexes.map { BtcAccount.fromSeed(seed, network, ScriptType.NATIVE_SEGWIT, it).watchOnly() }
        }
        val bitcoin = PairingApp(
            app = PairImport.BITCOIN,
            keys = bitcoinAccounts.map { PairingKey.bitcoinAccount(it) },
            accounts = bitcoinAccounts.indices.map { key ->
                PairingAccount(key = key, name = autoAccountName(BASE, key % ACCOUNTS), label = null, group = null)
            },
        )
        val solana = PairingApp(
            app = PairImport.SOLANA,
            keys = indexes.map { PairingKey.solanaAccount(it, SolanaAccount.publicKey(seed, it)) },
            accounts = indexes.map { PairingAccount(key = it, name = autoAccountName(BASE, it), label = null, group = null) },
        )
        val algorand = PairingApp(
            app = PairImport.ALGORAND,
            keys = indexes.map { PairingKey.algorandAccount(it, AlgorandAccount.publicKey(seed, it)) },
            accounts = indexes.map { PairingAccount(key = it, name = autoAccountName(BASE, it), label = null, group = null) },
        )
        return PairingPayload(
            fingerprint = HdWallet(seed).masterFingerprint.toLong() and 0xFFFFFFFFL,
            name = BASE,
            autoNameBase = BASE,
            apps = listOf(ethereum, bitcoin, solana, algorand),
        )
    }

    /** The body of a code written without compression: field 7 of its envelope, decoded. */
    private fun bodyOf(envelope: ByteArray): Cbor {
        val fields = (Cbor.decode(envelope) as Cbor.Dict).entries.associate { (key, value) -> (key as Cbor.Uint).value to value }
        return Cbor.decode((fields.getValue(BODY_FIELD) as Cbor.Bytes).value)
    }

    /** Every text item of [item], in the order it is written. */
    private fun textsOf(item: Cbor): List<String> = when (item) {
        is Cbor.Text -> listOf(item.value)
        is Cbor.Arr -> item.items.flatMap { textsOf(it) }
        is Cbor.Dict -> item.entries.flatMap { (key, value) -> textsOf(key) + textsOf(value) }
        is Cbor.Tagged -> textsOf(item.value)
        else -> emptyList()
    }

    private companion object {
        const val BASE = "Air"
        const val ACCOUNTS = 10
        const val CUSTOM_INDEX = 2
        const val CUSTOM_NAME = "PolarNova"

        /** The envelope field that holds the body of a pairing. */
        const val BODY_FIELD = 7L
    }
}
