package app.lensly.core.registry

import app.lensly.ui.shippedRegistry
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

private const val TOKEN = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val SPENDER = "9858effd232b4033e47d90003d41ec34ecaeda94"
private const val APPROVE = "095ea7b3"
private const val SET_APPROVAL_FOR_ALL = "a22cb465"
private const val INCREASE_ALLOWANCE = "39509351"
private const val TRANSFER = "a9059cbb"
private const val TRANSFER_OWNERSHIP = "f2fde38b"
private const val UPGRADE_TO = "3659cfe6"

/** Calldata for [selector] followed by 32-byte words, each given as hex without prefix. */
private fun calldata(selector: String, vararg words: String): ByteArray =
    Numeric.hexStringToByteArray("0x" + selector + words.joinToString("") { it.padStart(64, '0') })

private fun amount(value: BigInteger): String = value.toString(16)

/** The denylist that ships in the APK, applied the way the sign pipeline applies it. */
class DenylistTest {
    private val registry = shippedRegistry()

    private fun selectorsMatching(calldata: ByteArray, chainId: Long = 1, contract: String = TOKEN): List<String?> =
        registry.denylistMatches(chainId, contract, calldata).map { it.selector }

    @Test
    fun unlimitedApprovalMatchesTheApprovalEntry() {
        val data = calldata(APPROVE, SPENDER, amount(ContractCall.MAX_UINT256))

        assertEquals(listOf("0x095ea7b3"), selectorsMatching(data))
    }

    @Test
    fun aLimitedApprovalMatchesNothing() {
        val data = calldata(APPROVE, SPENDER, amount(BigInteger.valueOf(100_000_000)))

        assertEquals(emptyList(), selectorsMatching(data))
    }

    @Test
    fun setApprovalForAllWithTrueMatchesButWithFalseDoesNot() {
        assertEquals(listOf("0xa22cb465"), selectorsMatching(calldata(SET_APPROVAL_FOR_ALL, SPENDER, "1")))
        assertEquals(emptyList(), selectorsMatching(calldata(SET_APPROVAL_FOR_ALL, SPENDER, "0")))
    }

    @Test
    fun increaseAllowanceMatchesWhateverItsAmount() {
        assertEquals(listOf("0x39509351"), selectorsMatching(calldata(INCREASE_ALLOWANCE, SPENDER, "1")))
    }

    @Test
    fun anUnknownSelectorMatchesNothing() {
        assertEquals(emptyList(), selectorsMatching(calldata(TRANSFER, SPENDER, "a")))
    }

    @Test
    fun anApprovalThatCannotBeDecodedStillWarns() {
        // One word is not an approve(address,uint256). Lensly cannot read its amount, so the entry matches rather than hides it.
        assertEquals(listOf("0x095ea7b3"), selectorsMatching(calldata(APPROVE, SPENDER)))
    }

    @Test
    fun ownershipAndUpgradeSelectorsMatchAnyCall() {
        assertEquals(listOf("0xf2fde38b"), selectorsMatching(calldata(TRANSFER_OWNERSHIP, SPENDER)))
        assertEquals(listOf("0x3659cfe6"), selectorsMatching(calldata(UPGRADE_TO, SPENDER)))
    }

    @Test
    fun aTypedDataEntryMatchesNoTransaction() {
        val typed = registry.denylistEntries().single { it.primaryType == "Permit" }

        assertEquals(emptyList(), registry.denylistMatches(1, TOKEN, ByteArray(0)).filter { it == typed })
        assertEquals(emptyList(), matchingDenylist(listOf(typed), 1, TOKEN, calldata(APPROVE, SPENDER, "1")))
    }

    @Test
    fun aChainAndContractEntryMatchesOnlyThatPair() {
        val pinned = DenylistEntry(chainId = 1, contract = TOKEN.lowercase(), severity = DenylistSeverity.WARN, reason = "Pinned.")
        val entries = listOf(pinned)

        assertEquals(1, matchingDenylist(entries, 1, TOKEN, ByteArray(0)).size)
        assertEquals(1, matchingDenylist(entries, 1, TOKEN.uppercase().replace("0X", "0x"), ByteArray(0)).size)
        assertEquals(0, matchingDenylist(entries, 8453, TOKEN, ByteArray(0)).size)
        assertEquals(0, matchingDenylist(entries, 1, "0x1111111111111111111111111111111111111111", ByteArray(0)).size)
    }

    @Test
    fun theSeededSelectorsAreTheSelectorsOfTheirSignatures() {
        val signatures = listOf(
            "approve(address,uint256)",
            "setApprovalForAll(address,bool)",
            "increaseAllowance(address,uint256)",
            "transferOwnership(address)",
            "upgradeTo(address)",
            "upgradeToAndCall(address,bytes)",
        )
        val selectors = registry.denylistEntries().mapNotNull { it.selector }.toSet()

        assertEquals(signatures.map { Hash.sha3String(it).take(10) }.toSet(), selectors)
    }

    @Test
    fun theShippedDenylistSeedsEverySelectorAndTheTypedPermit() {
        val entries = registry.denylistEntries()

        assertEquals(7, entries.size)
        assertTrue(entries.all { it.severity == DenylistSeverity.WARN })
        assertTrue(entries.any { it.primaryType == "Permit" })
    }

    @Test
    fun invalidEntriesAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            DenylistEntry(contract = TOKEN.lowercase(), severity = DenylistSeverity.WARN, reason = "No chain.")
        }
        assertFailsWith<IllegalArgumentException> {
            DenylistEntry(selector = "0x095ea7b", severity = DenylistSeverity.WARN, reason = "Short selector.")
        }
        assertFailsWith<IllegalArgumentException> {
            DenylistEntry(severity = DenylistSeverity.WARN, reason = "Names nothing.")
        }
        assertFailsWith<IllegalArgumentException> {
            DenylistEntry(
                chainId = 1,
                contract = TOKEN.lowercase(),
                condition = DenylistCondition.UNLIMITED,
                severity = DenylistSeverity.WARN,
                reason = "A condition needs a selector.",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DenylistEntry(selector = "0x095ea7b3", severity = DenylistSeverity.WARN, reason = " ")
        }
    }
}
