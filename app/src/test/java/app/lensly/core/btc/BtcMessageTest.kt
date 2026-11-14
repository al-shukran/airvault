package app.lensly.core.btc

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector64
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class BtcMessageTest {
    private val seed = BitcoinVectors.seed()

    @Test
    fun bitcoinCoreMessageSignVectorReproduces() {
        val key = PrivateKey(ByteVector32(BitcoinVectors["message.core.privkey"]))
        assertEquals(
            BitcoinVectors["message.core.address"],
            Bitcoin.computeP2PkhAddress(key.publicKey(), Chain.Mainnet.chainHash),
        )
        assertEquals(
            BitcoinVectors["message.core.signature"],
            BtcMessage.signWith(key, headerBase = 31, message = BitcoinVectors["message.core.text"]),
        )
    }

    @Test
    fun headersFollowTheScriptTypeOfTheAccount() {
        val text = BitcoinVectors["message.hello.text"]
        val legacy = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.LEGACY)
        val nested = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NESTED_SEGWIT)
        val native = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        assertEquals(BitcoinVectors["message.hello.legacy.signature"], BtcMessage.sign(legacy, text))
        assertEquals(BitcoinVectors["message.hello.nested.signature"], BtcMessage.sign(nested, text))
        assertEquals(BitcoinVectors["message.hello.native.signature"], BtcMessage.sign(native, text))
    }

    @Test
    fun signaturesRecoverTheirAddressOnBothNetworks() {
        val text = "Lensly message check"
        for (network in BtcNetwork.entries) {
            for (type in listOf(ScriptType.LEGACY, ScriptType.NESTED_SEGWIT, ScriptType.NATIVE_SEGWIT)) {
                val account = BtcAccount.fromSeed(seed, network, type)
                val signature = BtcMessage.sign(account, text)
                assertEquals(account.receiveAddress(0), recoverAddress(network, text, signature), "${network.name} $type")
                val change = BtcMessage.sign(account, text, change = true, index = 2)
                assertEquals(account.changeAddress(2), recoverAddress(network, text, change), "${network.name} $type change")
            }
        }
    }

    @Test
    fun longMessagesUseTheWiderCompactSize() {
        val text = "a".repeat(300)
        val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        assertEquals(account.receiveAddress(0), recoverAddress(BtcNetwork.TESTNET4, text, BtcMessage.sign(account, text)))
    }

    @Test
    fun taprootHasNoBip137Header() {
        val account = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT)
        assertFailsWith<IllegalArgumentException> { BtcMessage.sign(account, "hello") }
    }

    /**
     * Recovers the address that signed [text], using the BIP137 header to read the address type and the recovery id. The
     * message hash is computed here from the spec, not from [BtcMessage], so the check does not reuse the code it verifies.
     */
    private fun recoverAddress(network: BtcNetwork, text: String, signature: String): String {
        val bytes = Base64.getDecoder().decode(signature)
        val header = bytes[0].toInt() and 0xFF
        val (type, recid) = when (header) {
            in 31..34 -> ScriptType.LEGACY to header - 31
            in 35..38 -> ScriptType.NESTED_SEGWIT to header - 35
            in 39..42 -> ScriptType.NATIVE_SEGWIT to header - 39
            else -> error("header $header is not a BIP137 segwit or P2PKH header")
        }
        val message = text.toByteArray()
        val preimage = "\u0018Bitcoin Signed Message:\n".toByteArray() + varint(message.size) + message
        val hash = Crypto.hash256(preimage)
        val key = Crypto.recoverPublicKey(bytes.copyOfRange(1, 65).byteVector64(), hash, recid)
        val chainHash = network.chain.chainHash
        return when (type) {
            ScriptType.LEGACY -> Bitcoin.computeP2PkhAddress(key, chainHash)
            ScriptType.NESTED_SEGWIT -> Bitcoin.computeP2ShOfP2WpkhAddress(key, chainHash)
            else -> Bitcoin.computeP2WpkhAddress(key, chainHash)
        }
    }

    private fun varint(size: Int): ByteArray = when {
        size < 0xFD -> byteArrayOf(size.toByte())
        else -> byteArrayOf(0xFD.toByte(), size.toByte(), (size shr 8).toByte())
    }
}
