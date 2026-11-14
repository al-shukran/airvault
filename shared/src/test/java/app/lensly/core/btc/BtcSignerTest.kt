package app.lensly.core.btc

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BtcSignerTest {
    private val seed = BitcoinVectors.seed()
    private val testnet4 = BtcNetwork.TESTNET4

    @Test
    fun everyScriptTypeIsReviewedSignedAndAcceptedByTheInterpreter() {
        for (type in ScriptType.entries) {
            val account = BtcAccount.fromSeed(seed, testnet4, type)
            val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
            val psbt = BtcFixtures.psbt(listOf(spend), paying(account, paid = 30_000L, change = 60_000L))
            val signer = BtcSigner(account)

            val review = signer.review(psbt)
            assertEquals(1, review.inputs.size)
            assertEquals(100_000L, review.inputs[0].amountSats)
            assertTrue(review.inputs[0].amountVerified, type.name)
            assertEquals(account.path.derive(0L).derive(0L), review.inputs[0].ownerPath, type.name)
            // The payment goes to a receive address, which is an outside Address even on this wallet.
            assertEquals(account.receiveAddress(5), review.outputs[0].address)
            assertFalse(review.outputs[0].isOwnChange)
            assertNull(review.outputs[0].changeIndex)
            // The change goes to change index 0, which is the user's own Change Output.
            assertEquals(account.changeAddress(0), review.outputs[1].address)
            assertTrue(review.outputs[1].isOwnChange)
            assertEquals(0, review.outputs[1].changeIndex)
            assertEquals(10_000L, review.feeSats, type.name)
            assertTrue(review.allInputsVerified)
            assertFalse(review.requiresConfirmation)

            val signed = signer.sign(psbt).psbt
            val tx = BtcFixtures.finalize(signed, listOf(spend))
            val prevouts = BtcFixtures.prevouts(listOf(spend))
            assertTrue(BtcFixtures.verifies(tx, prevouts), "${type.name}: interpreter rejects the signed transaction")
            // Negative control: an output changed after signing must fail, so the check above means something.
            val tampered = tx.copy(txOut = tx.txOut.mapIndexed { i, out -> if (i == 0) out.copy(amount = Satoshi(30_001L)) else out })
            assertFalse(BtcFixtures.verifies(tampered, prevouts), "${type.name}: a changed output still verifies")
        }
    }

    @Test
    fun twoOwnedInputsOfOneAccountAreBothSigned() {
        val account = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT)
        val spends = listOf(
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 40_000L, salt = 1), account, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(account, 1, 2, 70_000L, salt = 2), account, 1, 2),
        )
        // Inputs total 110,000 sats and outputs total 105,000 sats.
        val psbt = BtcFixtures.psbt(spends, paying(account, paid = 90_000L, change = 15_000L))
        val review = BtcSigner(account).review(psbt)
        assertEquals(5_000L, review.feeSats)
        assertEquals(
            listOf(account.path.derive(0L).derive(0L), account.path.derive(1L).derive(2L)),
            review.inputs.map { it.ownerPath },
        )

        val tx = BtcFixtures.finalize(BtcSigner(account).sign(psbt).psbt, spends)
        assertTrue(BtcFixtures.verifies(tx, BtcFixtures.prevouts(spends)))
    }

    @Test
    fun aForeignInputIsReviewedButNotSigned() {
        val account = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT)
        val foreign = BtcAccount.fromSeed(ByteArray(16) { 9 }, testnet4, ScriptType.NATIVE_SEGWIT)
        val spends = listOf(
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 60_000L, salt = 3), account, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(foreign, 0, 0, 50_000L, salt = 4), foreign, 0, 0),
        )
        // Inputs total 110,000 sats and outputs total 105,000 sats.
        val psbt = BtcFixtures.psbt(spends, paying(account, paid = 80_000L, change = 25_000L))
        val signed = BtcSigner(account).sign(psbt)

        assertNull(signed.review.inputs[1].ownerPath)
        assertTrue(signed.review.inputs[1].amountVerified)
        assertEquals(5_000L, signed.review.feeSats)
        assertTrue(signed.psbt.inputs[0].partialSigs.isNotEmpty())
        assertTrue(signed.psbt.inputs[1].partialSigs.isEmpty())
    }

    @Test
    fun changeIndexesOutsideTheKnownRangeAreOutsideAddresses() {
        val account = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val outputs = listOf(
            TxOut(Satoshi(1_000L), ByteVector(account.scriptPubKey(1, BtcAccount.GAP_LIMIT - 1))),
            TxOut(Satoshi(2_000L), ByteVector(account.scriptPubKey(1, BtcAccount.GAP_LIMIT))),
            TxOut(Satoshi(3_000L), ByteVector(account.scriptPubKey(1, 25))),
        )
        val review = BtcSigner(account).review(BtcFixtures.psbt(listOf(spend), outputs))
        assertTrue(review.outputs[0].isOwnChange)
        assertEquals(BtcAccount.GAP_LIMIT - 1, review.outputs[0].changeIndex)
        assertFalse(review.outputs[1].isOwnChange)
        assertFalse(review.outputs[2].isOwnChange)
        assertNull(review.outputs[2].changeIndex)
    }

    @Test
    fun aSignedPsbtKeepsItsMarkerAndRoundTripsThroughUrAndBase64() {
        val account = BtcAccount.fromSeed(seed, testnet4, ScriptType.TAPROOT)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcPsbt.withCompanionMarker(BtcFixtures.psbt(listOf(spend), paying(account, paid = 30_000L, change = 60_000L)))
        val signed = BtcSigner(account).sign(psbt).psbt
        assertTrue(BtcPsbt.hasCompanionMarker(signed))

        val bytes = Psbt.write(signed).toByteArray()
        val ur = BtcPsbt.encodeUr(signed)
        assertTrue(ur.startsWith("ur:crypto-psbt/"), ur.take(24))
        assertContentEquals(bytes, Psbt.write(BtcPsbt.decode(ur)).toByteArray())
        val base64 = Base64.getEncoder().encodeToString(bytes)
        assertContentEquals(bytes, Psbt.write(BtcPsbt.decode(base64)).toByteArray())
    }

    @Test
    fun anUpperCaseQrUrDecodesAndMalformedInputIsAnIllegalArgument() {
        val account = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), paying(account, paid = 30_000L, change = 60_000L))
        val ur = BtcPsbt.encodeUr(psbt)
        assertContentEquals(Psbt.write(psbt).toByteArray(), Psbt.write(BtcPsbt.decode(ur.uppercase())).toByteArray())
        assertFailsWith<IllegalArgumentException> { BtcPsbt.decode("ur:crypto-psbt/not-a-payload") }
        assertFailsWith<IllegalArgumentException> { BtcPsbt.decode("not a psbt !!") }
    }

    @Test
    fun theCompanionMarkerIsReplacedNotDuplicated() {
        val account = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), paying(account, paid = 30_000L, change = 60_000L))
        val once = BtcPsbt.withCompanionMarker(psbt)
        val twice = BtcPsbt.withCompanionMarker(once)
        assertEquals(once.global.unknown.size, twice.global.unknown.size)
        assertFalse(BtcPsbt.hasCompanionMarker(psbt))
    }

    /** The two outputs of a spend: a receive address that is an outside Address, and change at index 0. */
    private fun paying(account: BtcAccount, paid: Long, change: Long): List<TxOut> = listOf(
        TxOut(Satoshi(paid), ByteVector(account.scriptPubKey(0, 5))),
        TxOut(Satoshi(change), ByteVector(account.scriptPubKey(1, 0))),
    )
}
