package app.lensly.core.wallet

import java.math.BigInteger
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.math.ec.ECPoint
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys

/**
 * Ethereum addresses from public data.
 *
 * [addressAt] derives m/44'/60'/0'/0/i from an [AccountXpub] with BIP32 public derivation, so an address can be shown
 * without the seed. [addressOfPrivateKey] gives the address that a private key controls. The sign flow uses it to check
 * that the key it loaded belongs to the account on the review.
 */
object AccountAddresses {
    private val curve = SECNamedCurves.getByName("secp256k1")
    private val curveOrder: BigInteger = curve.n

    /** EIP-55 address of m/44'/60'/0'/0/[index] for the account described by [xpub]. [index] must not be hardened. */
    fun addressAt(xpub: AccountXpub, index: Int): String {
        require(index >= 0) { "index must be a non-hardened child number (0 until 2^31)" }
        require(xpub.publicKey.size == COMPRESSED_KEY_BYTES) { "compressed public key must be 33 bytes" }
        require(xpub.chainCode.size == CHAIN_CODE_BYTES) { "chain code must be 32 bytes" }
        val account = Node(curve.curve.decodePoint(xpub.publicKey), xpub.chainCode)
        return addressOf(publicChild(publicChild(account, 0), index).point)
    }

    /** EIP-55 address that controls [privateKey], a 32-byte secp256k1 key. The caller still owns the array. */
    fun addressOfPrivateKey(privateKey: ByteArray): String {
        require(privateKey.size == PRIVATE_KEY_BYTES) { "private key must be 32 bytes" }
        val key = BigInteger(1, privateKey)
        require(key.signum() > 0 && key < curveOrder) { "private key is out of range" }
        return addressOf(curve.g.multiply(key).normalize())
    }

    private class Node(val point: ECPoint, val chainCode: ByteArray)

    /** BIP32 CKDpub for a non-hardened child: the child point is IL*G + parent, and its chain code is IR. */
    private fun publicChild(parent: Node, index: Int): Node {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(parent.chainCode, "HmacSHA512"))
        val i = mac.doFinal(parent.point.getEncoded(true) + ByteBuffer.allocate(4).putInt(index).array())
        val il = BigInteger(1, i.copyOfRange(0, 32))
        check(il < curveOrder) { "child $index is invalid; the next index is used instead" }
        val child = curve.g.multiply(il).add(parent.point).normalize()
        check(!child.isInfinity) { "child $index is invalid; the next index is used instead" }
        return Node(child, i.copyOfRange(32, 64))
    }

    private fun addressOf(point: ECPoint): String {
        val uncompressed = point.getEncoded(false)
        val hash = Hash.sha3(uncompressed.copyOfRange(1, uncompressed.size))
        return Keys.toChecksumAddress(hash.copyOfRange(12, 32).toHex())
    }

    private const val COMPRESSED_KEY_BYTES = 33
    private const val CHAIN_CODE_BYTES = 32
    private const val PRIVATE_KEY_BYTES = 32
}
