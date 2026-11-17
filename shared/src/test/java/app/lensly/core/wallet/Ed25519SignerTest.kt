package app.lensly.core.wallet

import app.lensly.core.ur.Cbor
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaRequestCheck
import app.lensly.core.solana.SolanaVectors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

class Ed25519SignerTest {

    /** RFC 8032 section 7.1, TEST 1, copied from the RFC: the secret key, the public key, an empty message and the signature. */
    @Test
    fun rfc8032TestOneSignsTheEmptyMessage() {
        val secret = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val public = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        assertContentEquals(public, Ed25519Signer.publicKeyOf(secret))
        assertContentEquals(signature, Ed25519Signer.sign(secret, ByteArray(0)))
        assertTrue(Ed25519Signer.verify(public, ByteArray(0), signature))
    }

    /** [Ed25519Signer.sign] checks each signature against the key's public key, so every result must verify with that key. */
    @Test
    fun everySignatureVerifiesWithItsOwnKeyForAnyLength() {
        for (seed in 1..5) {
            val secret = ByteArray(32) { (it * seed + 3).toByte() }
            val public = Ed25519Signer.publicKeyOf(secret)
            for (length in listOf(0, 1, 31, 32, 33, 1024)) {
                val message = ByteArray(length) { (it + seed).toByte() }
                assertTrue(Ed25519Signer.verify(public, message, Ed25519Signer.sign(secret, message)))
            }
        }
    }

    @Test
    fun aSignatureOfOtherBytesOrByAnotherKeyDoesNotVerify() {
        val secret = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val public = Ed25519Signer.publicKeyOf(secret)
        val signature = Ed25519Signer.sign(secret, byteArrayOf(1, 2, 3))
        assertTrue(Ed25519Signer.verify(public, byteArrayOf(1, 2, 3), signature))
        assertFalse(Ed25519Signer.verify(public, byteArrayOf(1, 2, 4), signature))
        val other = Ed25519Signer.publicKeyOf(ByteArray(32) { 5 })
        assertFalse(Ed25519Signer.verify(other, byteArrayOf(1, 2, 3), signature))
        assertFalse(Ed25519Signer.verify(public, byteArrayOf(1, 2, 3), signature.copyOf(63)))
    }

    /**
     * Account 0 of each phrase signs a legacy transfer request, and the signature verifies with the account's public key. The first
     * phrase is plan section 4 Mnemonic A, the 12 words. The second is the 24-word phrase the assignment names, which the plan lists as
     * Mnemonic B.
     */
    @Test
    fun aLegacyTransferRequestForAccountZeroSignsAndVerifiesWithItsKey() {
        for (phrase in listOf(MNEMONIC_A, MNEMONIC_24)) {
            val seed = MnemonicUtils.generateSeed(phrase, "")
            val payer = SolanaAccount.publicKey(seed, 0)
            val signData = legacyTransfer(listOf(payer, ByteArray(32) { 9 }, systemProgram()), lamports = 1_500_000_000L)
            val request = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(requestCbor(signData))).request

            val review = assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, listOf(payer))).review
            assertEquals(0, review.accountIndex)
            val key = Slip10Ed25519.derive(seed, intArrayOf(Slip10Ed25519.hardened(44), Slip10Ed25519.hardened(501), Slip10Ed25519.hardened(0)))
                .use { it.privateKey() }
            val signature = try {
                Ed25519Signer.sign(key, request.signData)
            } finally {
                key.fill(0)
            }

            assertEquals(Ed25519Signer.SIGNATURE_BYTES, signature.size)
            assertTrue(Ed25519Signer.verify(payer, request.signData, signature))
        }
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun systemProgram(): ByteArray = fr.acinq.bitcoin.Base58.decode(SolanaVectors.SYSTEM)

    /** A legacy message with [keys]: one signer, the first key, and one System Program transfer of [lamports] from key 0 to key 1. */
    private fun legacyTransfer(keys: List<ByteArray>, lamports: Long): ByteArray {
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(lamports).array()
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 1))
        out.write(keys.size)
        keys.forEach { out.write(it) }
        out.write(ByteArray(32) { 7 })
        out.write(byteArrayOf(1, 2, 2, 0, 1, 12))
        out.write(data)
        return out.toByteArray()
    }

    /** A sol-sign-request for [signData] on the account path 44'/501'/0', as a wallet sends it. */
    private fun requestCbor(signData: ByteArray): ByteArray {
        val components = listOf(44L, 501L, 0L).flatMap { listOf(Cbor.Uint(it), Cbor.Bool(true)) }
        val keypath = Cbor.Tagged(304, Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(0x12121212L))))
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(ByteArray(16) { 0x11 })),
                    Cbor.Uint(2L) to Cbor.Bytes(signData),
                    Cbor.Uint(3L) to keypath,
                    Cbor.Uint(5L) to Cbor.Text("solflare"),
                    Cbor.Uint(6L) to Cbor.Uint(1L),
                ),
            ),
        )
    }

    private companion object {
        const val MNEMONIC_A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val MNEMONIC_24 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
    }
}
