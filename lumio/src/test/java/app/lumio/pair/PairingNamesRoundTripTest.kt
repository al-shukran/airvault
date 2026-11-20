package app.lumio.pair

import app.lensly.app.autoAccountName
import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.bitcoin.bitcoinTestnet4
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.solana.SOLANA_MAINNET
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.Cbor
import app.lumio.data.net.Networks
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
            (listOf("Air", "Air", "PolarNova") + listOf(ethereumMainnet, bitcoinMainnet, bitcoinTestnet4, SOLANA_MAINNET, algorandMainnet).map { it.caip2 }).sorted(),
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
            PairImport.accountsOf(read, Networks.EIP155).map { it.name },
        )
        val autoNames = (0 until ACCOUNTS).map { autoAccountName(BASE, it) }
        assertEquals(autoNames + autoNames, PairImport.accountsOf(read, Networks.BIP122).map { it.name })
        assertEquals(autoNames, PairImport.accountsOf(read, Networks.SOLANA).map { it.name })
        assertEquals(autoNames, PairImport.accountsOf(read, Networks.ALGORAND).map { it.name })
    }

    /** The pairing of the scenario, as Lensly's Apps build it: each account carries its name, and its Auto-Name when it has none. */
    private fun scenario(): PairingPayload {
        val seed = PairFixtures.seed
        val indexes = 0 until ACCOUNTS
        val ethereum = indexes.map { index ->
            PairFixtures.ethereumAccount(index, if (index == CUSTOM_INDEX) CUSTOM_NAME else autoAccountName(BASE, index))
        }
        val bitcoin = listOf(BtcNetwork.MAINNET, BtcNetwork.TESTNET4).flatMap { network ->
            indexes.map { index ->
                PairFixtures.bitcoinAccount(BtcAccount.fromSeed(seed, network, ScriptType.NATIVE_SEGWIT, index).watchOnly(), autoAccountName(BASE, index))
            }
        }
        val solana = indexes.map { PairFixtures.solanaAccount(it, autoAccountName(BASE, it)) }
        val algorand = indexes.map { PairFixtures.algorandAccount(it, autoAccountName(BASE, it)) }
        return PairFixtures.payloadOf(ethereum + bitcoin + solana + algorand, name = BASE, base = BASE)
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
