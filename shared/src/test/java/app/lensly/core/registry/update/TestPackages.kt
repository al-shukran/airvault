package app.lensly.core.registry.update

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Signed registry packages for tests. The layout is written out here from the spec rather than taken from
 * [PackageFormat], so a reader bug cannot hide itself. These are test keys, never a key a build trusts.
 */
internal object TestPackages {
    val SEED = ByteArray(32) { (it * 3 + 7).toByte() }
    val PUBLIC_BYTES: ByteArray = Ed25519PrivateKeyParameters(SEED, 0).generatePublicKey().encoded

    /** The test key as a build lists it: active, so its packages are accepted. */
    val TRUSTED: List<TrustedKey> = listOf(TrustedKey(PUBLIC_BYTES))

    const val LINEA_NETWORK =
        """{"chainId":4242,"explorerName":"Lineascan","feeModel":"L2_ROLLUP","name":"Linea","nativeDecimals":18,"nativeSymbol":"ETH","testnet":false}"""
    const val LINEA_USDC =
        """{"chainId":4242,"contract":"0x176211869ca2b568f2a7d4ee941e073a821ee1ff","decimals":6,"name":"USDC","symbol":"USDC"}"""
    const val LINEA_SWAP =
        """{"chainId":4242,"contract":"0x5555555555555555555555555555555555555555","meaning":"Swap tokens on the test route","params":[{"name":"amountIn","type":"uint256","tokenRef":true},{"name":"amountOutMin","type":"uint256"},{"name":"to","type":"address"}],"proxy":null,"selector":"0x6e17e9f6","signature":"swapExactTokensForTokens(uint256,uint256,address)","upgradeable":false}"""

    /**
     * A payload holding [networks] and [tokens], which are already JSON text. A whitelist, also JSON text, is added only when
     * given. [minSchemaVersion] is the catalog schema the package needs.
     */
    fun payload(
        sequence: Long = 1,
        networks: String = LINEA_NETWORK,
        tokens: String = LINEA_USDC,
        formatVersion: Int = 2,
        minSchemaVersion: Int = 1,
        createdAt: String = "2026-11-10",
        whitelist: String? = null,
    ): String {
        val approvals = whitelist?.let { ""","whitelist":[$it]""" }.orEmpty()
        return """{"createdAt":"$createdAt","formatVersion":$formatVersion,"minSchemaVersion":$minSchemaVersion,""" +
            """"networks":[$networks],"sequence":$sequence,"tokens":[$tokens]$approvals}"""
    }

    fun publicKeyOf(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun signed(payload: String, seed: ByteArray = SEED): ByteArray =
        signedBytes(payload.toByteArray(StandardCharsets.UTF_8), seed)

    /** Package bytes wrapping a raw [payload], signed with [seed] under the key ID of its public key. */
    fun signedBytes(payload: ByteArray, seed: ByteArray = SEED): ByteArray {
        val keyId = MessageDigest.getInstance("SHA-256").digest(publicKeyOf(seed)).copyOf(8)
        val message = "LENSLY-REGISTRY-v2\n".toByteArray(StandardCharsets.US_ASCII) + keyId + payload
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return ByteBuffer.allocate(16 + payload.size + 64)
            .put("LRP2".toByteArray(StandardCharsets.US_ASCII))
            .putInt(payload.size)
            .put(keyId)
            .put(payload)
            .put(signer.generateSignature())
            .array()
    }
}
