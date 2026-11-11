package app.lensly.core.registry.update

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Signed registry packages for tests. The layout is written out here from the spec rather than taken from
 * [PackageFormat], so a reader bug cannot hide itself. These are test keys, never the baked release key.
 */
internal object TestPackages {
    val SEED = ByteArray(32) { (it * 3 + 7).toByte() }
    val PUBLIC_BYTES: ByteArray = Ed25519PrivateKeyParameters(SEED, 0).generatePublicKey().encoded

    const val LINEA_NETWORK =
        """{"chainId":4242,"explorerName":"Lineascan","feeModel":"L2_ROLLUP","name":"Linea","nativeDecimals":18,"nativeSymbol":"ETH","testnet":false}"""
    const val LINEA_USDC =
        """{"chainId":4242,"contract":"0x176211869ca2b568f2a7d4ee941e073a821ee1ff","decimals":6,"name":"USDC","symbol":"USDC"}"""
    const val LINEA_SWAP =
        """{"chainId":4242,"contract":"0x5555555555555555555555555555555555555555","meaning":"Swap tokens on the test route","params":[{"name":"amountIn","type":"uint256","tokenRef":true},{"name":"amountOutMin","type":"uint256"},{"name":"to","type":"address"}],"proxy":null,"selector":"0x6e17e9f6","signature":"swapExactTokensForTokens(uint256,uint256,address)","upgradeable":false}"""

    /**
     * A payload holding [networks] and [tokens], which are already JSON text. A whitelist, also JSON text, is added only when
     * given, so the payload is the one a package signed before the whitelist existed would hold.
     */
    fun payload(
        sequence: Long = 1,
        networks: String = LINEA_NETWORK,
        tokens: String = LINEA_USDC,
        formatVersion: Int = 1,
        createdAt: String = "2026-11-10",
        whitelist: String? = null,
    ): String {
        val approvals = whitelist?.let { ""","whitelist":[$it]""" }.orEmpty()
        return """{"createdAt":"$createdAt","formatVersion":$formatVersion,"networks":[$networks],"sequence":$sequence,"tokens":[$tokens]$approvals}"""
    }

    fun signed(payload: String, seed: ByteArray = SEED): ByteArray =
        signedBytes(payload.toByteArray(StandardCharsets.UTF_8), seed)

    /** Package bytes wrapping a raw [payload], signed with [seed]. */
    fun signedBytes(payload: ByteArray, seed: ByteArray = SEED): ByteArray {
        val message = "LENSLY-REGISTRY-v1\n".toByteArray(StandardCharsets.US_ASCII) + payload
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return ByteBuffer.allocate(8 + payload.size + 64)
            .put("LRP1".toByteArray(StandardCharsets.US_ASCII))
            .putInt(payload.size)
            .put(payload)
            .put(signer.generateSignature())
            .array()
    }
}
