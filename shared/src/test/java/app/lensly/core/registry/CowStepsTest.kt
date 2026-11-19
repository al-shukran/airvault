package app.lensly.core.registry

import app.airvault.abi.AbiDecoder
import app.airvault.abi.AbiValue
import app.airvault.abi.CallDecode
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

private const val COW_SETTLEMENT = "0x9008d19f58aabd9ed0d60971565aa8510560ab41"
private const val PRE_SIGN_SELECTOR = "0xec6cb13f"
private const val PRE_SIGN_SIGNATURE = "setPreSignature(bytes,bool)"
private const val OWNER = "0xdc7f593958ef44914f806a3196a869ed471cafc1"
private const val FUZZ_ROUNDS = 2000

/** The arguments of `setPreSignature(bytes,bool)`, in the order the approved entry names them. */
private val PRE_SIGN_PARAMS = listOf(WhitelistParam("orderUid", "bytes"), WhitelistParam("signed", "bool"))

/** The decoded arguments of the vector, which is a signed pre-signature of an order owned by [OWNER]. */
private fun vectorParams(): List<DecodedParam> =
    (AbiDecoder.decode(Numeric.hexStringToByteArray(StepVectors.COW_PRESIGN_ORDER), PRE_SIGN_PARAMS) as CallDecode.Ok).params

class CowStepsTest {

    @Test
    fun aPreSignatureOfTheSignersOrderSaysWhenItExpires() {
        // Transaction 0x0f29ddc8, CoW settlement: pre-sign an order owned by the sender, valid until 2026-11-16 03:43 UTC.
        val steps = assertNotNull(
            CallSteps.describe(STEP_REGISTRY, 1, COW_SETTLEMENT, PRE_SIGN_SELECTOR, PRE_SIGN_SIGNATURE, OWNER, BigInteger.ZERO, vectorParams()),
        )

        assertEquals(listOf("Pre-sign CoW order 0x9153…8843 for you, valid until 2026-11-16 03:43 UTC"), steps.lines)
        assertEquals(listOf("Solvers may fill this order on its terms until it expires. Revoke it to cancel."), steps.warnings)
    }

    @Test
    fun aPreSignatureOfAnotherAccountsOrderIsNotAStepAndWarns() {
        val steps = assertNotNull(
            CallSteps.describe(STEP_REGISTRY, 1, COW_SETTLEMENT, PRE_SIGN_SELECTOR, PRE_SIGN_SIGNATURE, SIGNER, BigInteger.ZERO, vectorParams()),
        )

        assertEquals(emptyList(), steps.lines)
        assertEquals(1, steps.warnings.size)
        assertTrue(steps.warnings.single().endsWith("not you, so the call reverts"), steps.warnings.single())
    }

    @Test
    fun aRevocationIsNotReadAndWarns() {
        val revoke = vectorParams().mapIndexed { index, param ->
            if (index == 1) DecodedParam(param.param, AbiValue.Word("00".repeat(WORD))) else param
        }

        val steps = assertNotNull(
            CallSteps.describe(STEP_REGISTRY, 1, COW_SETTLEMENT, PRE_SIGN_SELECTOR, PRE_SIGN_SIGNATURE, OWNER, BigInteger.ZERO, revoke),
        )

        assertEquals(emptyList(), steps.lines)
        assertEquals(listOf("Lensly cannot read this call. Its raw arguments are shown under Details."), steps.warnings)
    }

    @Test
    fun randomOrderUidsNeverThrowAndAnUnreadCallAlwaysWarns() {
        val random = Random(20261116)
        repeat(FUZZ_ROUNDS) {
            val uid = ByteArray(random.nextInt(0, 80)) { random.nextInt(0, 256).toByte() }
            val params = listOf(
                DecodedParam(PRE_SIGN_PARAMS[0], AbiValue.Dynamic(uid.toHex())),
                DecodedParam(PRE_SIGN_PARAMS[1], AbiValue.Word("00".repeat(WORD - 1) + "01")),
            )

            val steps = assertNotNull(
                CallSteps.describe(STEP_REGISTRY, 1, COW_SETTLEMENT, PRE_SIGN_SELECTOR, PRE_SIGN_SIGNATURE, OWNER, BigInteger.ZERO, params),
            )

            assertTrue(steps.lines.isNotEmpty() || steps.warnings.isNotEmpty())
            if (steps.lines.isEmpty()) assertTrue(steps.warnings.isNotEmpty())
        }
    }

    private companion object {
        const val WORD = 32
    }
}
