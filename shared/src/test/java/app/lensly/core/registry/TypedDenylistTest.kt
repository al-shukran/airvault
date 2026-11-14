package app.lensly.core.registry

import app.lensly.ui.shippedRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val USDC_ADDRESS = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val PERMIT_REASON =
    "A signed permit lets a spender move your tokens without a transaction. Scam sites use it to drain wallets."

/** The denylist that matches typed data (EIP-712): a Permit entry is a warning on its review, and a BLOCK entry refuses it. */
class TypedDenylistTest {
    private val registry = shippedRegistry()

    @Test
    fun aPermitRequestMatchesTheShippedWarnEntryWithItsReason() {
        val match = registry.typedDenylistMatches(chainId = 1, verifyingContract = USDC_ADDRESS, primaryType = "Permit").single()

        assertEquals(DenylistSeverity.WARN, match.severity)
        assertEquals(PERMIT_REASON, match.reason)
    }

    @Test
    fun thePermitEntryNamesNoChainOrContractSoAPermitOnAnyChainMatches() {
        assertEquals(1, registry.typedDenylistMatches(chainId = 8453, verifyingContract = null, primaryType = "Permit").size)
    }

    @Test
    fun aTypedRequestOfAnotherTypeReachesReview() {
        assertTrue(registry.typedDenylistMatches(1, USDC_ADDRESS, "PermitSingle").isEmpty())
        assertTrue(registry.typedDenylistMatches(1, USDC_ADDRESS, "PermitTransferFrom").isEmpty())
        assertTrue(registry.typedDenylistMatches(1, USDC_ADDRESS, "Mail").isEmpty())
    }

    @Test
    fun aSelectorEntryNeverMatchesTypedData() {
        assertTrue(registry.typedDenylistMatches(1, USDC_ADDRESS, "approve").isEmpty())
        assertTrue(registry.denylistEntries().filter { it.selector != null }.all { it.primaryType == null })
    }

    @Test
    fun aPrimaryTypeEntryMatchesOnlyItsChainAndContractWhenItNamesThem() {
        val pinned = DenylistEntry(
            chainId = 1,
            contract = USDC_ADDRESS.lowercase(),
            primaryType = "Permit",
            severity = DenylistSeverity.BLOCK,
            reason = "Pinned.",
        )
        val entries = listOf(pinned)

        assertEquals(1, matchingTypedDenylist(entries, 1, USDC_ADDRESS, "Permit").size)
        assertEquals(1, matchingTypedDenylist(entries, 1, USDC_ADDRESS.uppercase().replace("0X", "0x"), "Permit").size)
        assertEquals(0, matchingTypedDenylist(entries, 8453, USDC_ADDRESS, "Permit").size)
        assertEquals(0, matchingTypedDenylist(entries, 1, "0x1111111111111111111111111111111111111111", "Permit").size)
        assertEquals(0, matchingTypedDenylist(entries, 1, null, "Permit").size)
        assertEquals(0, matchingTypedDenylist(entries, 1, USDC_ADDRESS, "PermitSingle").size)
    }

    @Test
    fun aPrimaryTypeEntryNeverMatchesATransactionEvenAtItsContract() {
        val pinned = DenylistEntry(
            chainId = 1,
            contract = USDC_ADDRESS.lowercase(),
            primaryType = "Permit",
            severity = DenylistSeverity.WARN,
            reason = "Pinned.",
        )

        assertEquals(0, matchingDenylist(listOf(pinned), 1, USDC_ADDRESS, ByteArray(0)).size)
    }

    @Test
    fun anEntryNamingASelectorAndAPrimaryTypeIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            DenylistEntry(selector = "0x095ea7b3", primaryType = "Permit", severity = DenylistSeverity.WARN, reason = "Both.")
        }
    }
}
