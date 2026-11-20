package app.lensly.core.btc

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector64
import java.util.Base64
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Checks the BIP137 vectors by recovering the address that signed each one. The app never signs a message, so nothing here signs either.
 */
class BtcMessageTest {
    private val seed = BitcoinVectors.seed()

    @Test
    fun bitcoinCoreMessageVectorRecoversItsAddress() {
        val key = PrivateKey(ByteVector32(BitcoinVectors["message.core.privkey"]))
        assertEquals(
            BitcoinVectors["message.core.address"],
            Bitcoin.computeP2PkhAddress(key.publicKey(), Chain.Mainnet.chainHash),
        )
        assertEquals(
            BitcoinVectors["message.core.address"],
            recoverAddress(BtcNetwork.MAINNET, BitcoinVectors["message.core.text"], BitcoinVectors["message.core.signature"]),
        )
    }

    @Test
    fun headersFollowTheScriptTypeOfTheAccount() {
        val text = BitcoinVectors["message.hello.text"]
        val legacy = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.LEGACY)
        val nested = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NESTED_SEGWIT)
        val native = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        assertEquals(
            legacy.receiveAddress(0),
            recoverAddress(BtcNetwork.MAINNET, text, BitcoinVectors["message.hello.legacy.signature"]),
        )
        assertEquals(
            nested.receiveAddress(0),
            recoverAddress(BtcNetwork.MAINNET, text, BitcoinVectors["message.hello.nested.signature"]),
        )
        assertEquals(
            native.receiveAddress(0),
            recoverAddress(BtcNetwork.MAINNET, text, BitcoinVectors["message.hello.native.signature"]),
        )
    }

    /**
     * Recovers the address that signed [text], using the BIP137 header to read the address type and the recovery id. The
     * message hash is computed here from the spec, so the check does not depend on any code it verifies.
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
