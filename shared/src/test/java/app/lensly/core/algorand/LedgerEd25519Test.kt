package app.lensly.core.algorand

import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Test
import org.web3j.utils.Numeric

/**
 * Vectors for [LedgerEd25519].
 *
 * The Speculos seed is Speculos's default seed (`src/environment.c`, master 679182f8, LGPL-3.0): its 24-word phrase
 * is in a comment there, and the 64 bytes are the array `default_seed`. The account 123 vector is from
 * `tests/test_pubkey_cmd.py` in LedgerHQ/app-algorand (develop 62adedc7, MIT). The Mnemonic A rows are the research
 * values in the plan, section 4.4: computed by a port of the same scheme and not yet checked on a device.
 */
class LedgerEd25519Test {

    @Test
    fun speculosDefaultSeedIsTheBip39SeedOfItsPhrase() {
        assertEquals(SPECULOS_SEED_HEX, Mnemonic.toSeed(SPECULOS_PHRASE.split(' ')).toHex())
    }

    @Test
    fun matchesTheLedgerAppTestVectorForAccount123() {
        val key = LedgerEd25519.algorandAccount(Numeric.hexStringToByteArray(SPECULOS_SEED_HEX), 123)
        try {
            assertEquals(LEDGER_APP_PUBLIC_KEY, key.publicKey.toHex())
            assertEquals(LEDGER_APP_ADDRESS, AlgoAddress.encode(key.publicKey))
        } finally {
            key.wipe()
        }
    }

    @Test
    fun matchesTheMnemonicAVectorsForAccounts0To2() {
        val seed = Mnemonic.toSeed(MNEMONIC_A)
        for ((account, publicKey, address) in MNEMONIC_A_VECTORS) {
            val key = LedgerEd25519.algorandAccount(seed, account)
            try {
                assertEquals(publicKey, key.publicKey.toHex(), "public key of account $account")
                assertEquals(address, AlgoAddress.encode(key.publicKey), "address of account $account")
            } finally {
                key.wipe()
            }
        }
    }

    @Test
    fun wipeZeroesTheSecretHalvesAndKeepsThePublicKey() {
        val key = LedgerEd25519.algorandAccount(Numeric.hexStringToByteArray(SPECULOS_SEED_HEX), 123)
        val publicKey = key.publicKey.copyOf()
        key.wipe()
        assertTrue(key.kL.all { it == 0.toByte() }, "kL is zeroed by the wipe")
        assertTrue(key.kR.all { it == 0.toByte() }, "kR is zeroed by the wipe")
        assertContentEquals(publicKey, key.publicKey)
    }

    @Test
    fun doesNotProduceTheXhdAddressForAccount0() {
        val key = LedgerEd25519.algorandAccount(Mnemonic.toSeed(MNEMONIC_A), 0)
        try {
            assertNotEquals(XHD_PEIKERT_ACCOUNT_0, AlgoAddress.encode(key.publicKey))
        } finally {
            key.wipe()
        }
    }

    @Test
    fun signatureFromTheExpandedKeyVerifiesAgainstTheDerivedPublicKey() {
        val key = LedgerEd25519.algorandAccount(Numeric.hexStringToByteArray(SPECULOS_SEED_HEX), 123)
        try {
            val message = "AirVault Algorand signing check".toByteArray()
            val (expandedPublicKey, signature) = signFromExpandedKey(key.kL, message)
            assertContentEquals(key.publicKey, expandedPublicKey)
            assertTrue(verify(key.publicKey, message, signature), "BouncyCastle rejects the signature")
            assertFalse(verify(key.publicKey, message + byteArrayOf(0x00), signature), "a changed message verifies")
            // RFC 8032 signatures are deterministic: BouncyCastle's signer over the seed kL gives the same bytes.
            assertContentEquals(seedSignature(key.kL, message), signature)
        } finally {
            key.wipe()
        }
    }

    /**
     * RFC 8032 section 5.1.6 from the expanded key. SHA-512(kL) gives the scalar a (clamped) and the prefix. The device
     * signs this way with its 32-byte kL. The signature is built here step by step, not by BouncyCastle's seed signer,
     * so the test checks the expansion itself.
     */
    private fun signFromExpandedKey(kL: ByteArray, message: ByteArray): Pair<ByteArray, ByteArray> {
        val expanded = sha512(kL)
        expanded[0] = (expanded[0].toInt() and 0xF8).toByte()
        expanded[31] = ((expanded[31].toInt() and 0x7F) or 0x40).toByte()
        val scalar = expanded.copyOfRange(0, 32)
        val prefix = expanded.copyOfRange(32, 64)
        val publicKey = LedgerEd25519.scalarMultBaseEncoded(scalar)
        val r = littleEndian(sha512(prefix + message)).mod(ORDER)
        val nonce = LedgerEd25519.scalarMultBaseEncoded(leBytes(r))
        val k = littleEndian(sha512(nonce + publicKey + message)).mod(ORDER)
        val s = r.add(k.multiply(littleEndian(scalar))).mod(ORDER)
        return publicKey to (nonce + leBytes(s))
    }

    private fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    private fun seedSignature(kL: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(kL, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun leBytes(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        return ByteArray(32) { i ->
            val at = be.size - 1 - i
            if (at >= 0) be[at] else 0.toByte()
        }
    }

    private companion object {
        const val SPECULOS_PHRASE =
            "glory promote mansion idle axis finger extra february uncover one trip resource lawn turtle " +
                "enact monster seven myth punch hobby comfort wild raise skin"
        const val SPECULOS_SEED_HEX =
            "b11997faff420a331bb4a4ffdc8bdc8ba7c01732a99a30d83dbbebd469666c84b47d09d3" +
                "f5f472b3b9384ac634beba2a440ba36ec7661144132f35e206873564"
        const val LEDGER_APP_PUBLIC_KEY = "0dfdbcdb8eebed628cfb4ef70207b86fd0deddca78e90e8c59d6f441e383b377"
        const val LEDGER_APP_ADDRESS = "BX63ZW4O5PWWFDH3J33QEB5YN7IN5XOKPDUQ5DCZ232EDY4DWN3XKUQRCA"
        const val XHD_PEIKERT_ACCOUNT_0 = "IYRGHE7NYTZRYPGFFNVPH7TVCZVQLQFK2IJ57W5JONTHT4BZEYHXL3MY3E"

        /** The Ed25519 group order L = 2^252 + 27742317777372353535851937790883648493. */
        val ORDER: BigInteger =
            BigInteger.valueOf(2).pow(252).add(BigInteger("27742317777372353535851937790883648493"))

        /** BIP-39 test mnemonic: 23 "abandon" then "art" (entropy all zero). */
        val MNEMONIC_A: List<String> = List(23) { "abandon" } + "art"

        /** Account, public key (hex), address for Mnemonic A, path `m/44'/283'/N'/0/0`. Plan section 4.4. */
        val MNEMONIC_A_VECTORS = listOf(
            Triple(
                0,
                "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538",
                "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ",
            ),
            Triple(
                1,
                "c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615d",
                "YEO767URJECB5BKVVIH4ZOBCN5EHGHLU5RCQPRBSCPQ3OBPVMFOYGSM47U",
            ),
            Triple(
                2,
                "8d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006d",
                "RU7NDDFYFPLS5K42DPBBU57OWCL3P7LI7FZ7OBQDNUQZUFS5ABWULNOFFM",
            ),
        )
    }
}
