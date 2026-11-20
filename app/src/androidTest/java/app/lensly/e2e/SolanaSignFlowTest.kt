package app.lensly.e2e

import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lensly.app.export.partsOf
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.ui.flow.SignStep
import com.sparrowwallet.hummingbird.UR
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Solana signing flow, end to end, on a device: a Solana account is added to the test wallet, and a `sol-sign-request` for a legacy
 * SOL transfer from it is fed to the scanner through the real [app.lensly.ui.flow.SignWalletRoute]. The review shows the amount and the
 * recipient, the owner approves it with the App Password, and the `sol-signature` that comes back is checked as an Ed25519 signature over
 * the message bytes, by the account's public key derived from the seed.
 */
@RunWith(AndroidJUnit4::class)
class SolanaSignFlowTest : SignFlowE2eBase() {

    @Test
    fun solanaTransferIsReviewedApprovedAndAnsweredWithAVerifyingSignature() {
        val wallet = openWallet { store, id -> store.addSolanaAccounts(id, 1, BulkCap.DEFAULT.accounts) }
        val signer = SolanaAccount.publicKey(E2E_SEED, 0)
        val recipient = ByteArray(32) { 9 }
        val signData = legacyTransfer(signer, recipient, lamports = 1_000L)
        val requestId = ByteArray(16) { 0x11 }
        val request = requestCbor(signData, requestId, wallet.fingerprint)

        val driver = driverFor(wallet, partsOf(framesOf(UR("sol-sign-request", request), DEFAULT_MAX_FRAGMENT)))
        driver.open()
        val ready = (driver.awaitReview { it is SignStep.AppReview } as SignStep.AppReview).ready

        assertEquals(SolanaAccount.addressOf(signer), ready.signer.address)
        // One SOL transfer of 1,000 lamports, which is 0.000001 SOL, to the recipient.
        compose.onNodeWithText("Send 0.000001 SOL from ${SolanaAccount.addressOf(signer)} to ${SolanaAccount.addressOf(recipient)}?")
            .assertExists()
        driver.readToTheEnd()
        assertTrue(driver.approvable())

        val parts = driver.approveAndSign { it is SignStep.AppConfirming }
        val fields = fieldsOf(urOfParts(parts, "sol-signature"))
        assertArrayEquals(requestId, ((fields.getValue(1L) as Cbor.Tagged).value as Cbor.Bytes).value)
        val signature = (fields.getValue(2L) as Cbor.Bytes).value
        assertEquals(64, signature.size)
        assertTrue("the signature must verify under the account key", Ed25519Signer.verify(signer, signData, signature))
        // A negative control: the same signature over any other bytes must fail, so the check above means something.
        assertFalse(Ed25519Signer.verify(signer, signData + byteArrayOf(0), signature))
    }

    /**
     * A legacy transaction with one System Program transfer of [lamports] from [signer] to [recipient]. The signer is the one signing account
     * and the fee payer. The System Program is the all-zero address, which is `11111111111111111111111111111111` in base58.
     */
    private fun legacyTransfer(signer: ByteArray, recipient: ByteArray, lamports: Long): ByteArray {
        val transfer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(lamports).array()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 1)) // one signature, no read-only signed accounts, one read-only unsigned account
        out.write(3) // three account keys
        out.write(signer)
        out.write(recipient)
        out.write(ByteArray(32)) // the System Program
        out.write(ByteArray(32) { 7 }) // a recent blockhash
        out.write(byteArrayOf(1, 2, 2, 0, 1, 12)) // one instruction: program index 2, accounts 0 and 1, twelve bytes of data
        out.write(transfer)
        return out.toByteArray()
    }

    /** A `sol-sign-request` for [signData] on account 0 of the wallet with [fingerprint], as a wallet sends it. */
    private fun requestCbor(signData: ByteArray, requestId: ByteArray, fingerprint: Int): ByteArray {
        val components = listOf(44L, 501L, 0L).flatMap { index -> listOf(Cbor.Uint(index), Cbor.Bool(true)) }
        val keypath = Cbor.Tagged(
            304L,
            Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(fingerprint.toLong() and 0xFFFFFFFFL))),
        )
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(37L, Cbor.Bytes(requestId)),
                    Cbor.Uint(2L) to Cbor.Bytes(signData),
                    Cbor.Uint(3L) to keypath,
                    Cbor.Uint(5L) to Cbor.Text("solflare"),
                    Cbor.Uint(6L) to Cbor.Uint(1L),
                ),
            ),
        )
    }
}
