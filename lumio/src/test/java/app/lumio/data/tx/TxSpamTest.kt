package app.lumio.data.tx

import app.lumio.data.ETH
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The spam heuristic, one case per pattern, and the cases where it must stay silent. */
class TxSpamTest {
    private val account = "0x" + "ab".repeat(20)
    private val hash = "0x" + "1a".repeat(32)
    private val usdc = "0x" + "ee".repeat(20)
    private val listedToken = "0x" + "aa".repeat(20)
    private val friend = "0x1234" + "a".repeat(32) + "9f9f"
    private val lookalike = "0x1234" + "b".repeat(32) + "9f9f"
    private val stranger = "0x5678" + "c".repeat(32) + "0a0a"
    private val testnet = NetworkId(Networks.evm(11155111), testnet = true)
    private val spam = TxSpam(isKnownToken = { _, contract -> contract == listedToken }, isCustomToken = { _, _ -> false })

    private fun token(
        from: String = stranger,
        amount: BigInteger = BigInteger.valueOf(1_000_000),
        contract: String = usdc,
        name: String = "USD Coin",
        symbol: String = "USDC",
        decimals: Int = 6,
        direction: TxDirection = TxDirection.IN,
        network: NetworkId = ETH,
    ): LumioTx = LumioTx(
        id = "EVM:1:$hash:$contract",
        network = network,
        account = account,
        hash = hash,
        timestampMs = 0L,
        asset = TxAsset(contract, symbol, name, decimals),
        amount = amount,
        direction = direction,
        fee = null,
        counterparty = from,
        status = TxStatus.CONFIRMED,
        confirmations = 100L,
        inputData = null,
        memo = null,
    )

    @Test
    fun aListedTokenIsNeverSpam() {
        assertTrue(spam.reasons(token(amount = BigInteger.ZERO, contract = listedToken), emptySet()).isEmpty())
    }

    @Test
    fun aNativeCoinIsNeverSpam() {
        val native = token(amount = BigInteger.ZERO).copy(asset = TxAsset(null, "ETH", "Ether", 18))

        assertTrue(spam.reasons(native, setOf(friend)).isEmpty())
    }

    @Test
    fun aTestnetTransferIsNeverSpam() {
        assertTrue(spam.reasons(token(amount = BigInteger.ZERO, network = testnet), emptySet()).isEmpty())
        assertTrue(spam.reasons(token(from = lookalike, network = testnet), setOf(friend)).isEmpty())
    }

    @Test
    fun aTokenTheOwnerAddedIsNeverSpam() {
        val owned = TxSpam(isKnownToken = { _, _ -> false }, isCustomToken = { _, contract -> contract == usdc })

        assertTrue(owned.reasons(token(amount = BigInteger.ZERO), emptySet()).isEmpty())
        assertTrue(owned.reasons(token(from = lookalike), setOf(friend)).isEmpty())
    }

    @Test
    fun aZeroValueTransferOfAnUnlistedTokenIsFlagged() {
        assertEquals(setOf(SpamReason.ZERO_VALUE), spam.reasons(token(amount = BigInteger.ZERO), emptySet()))
    }

    @Test
    fun aDustTransferIsFlaggedAndOneMillionthOfAWholeTokenIsNot() {
        val dust = token(amount = BigInteger.ONE, decimals = 18)
        val oneMillionth = token(amount = BigInteger.TEN.pow(12), decimals = 18)

        assertEquals(setOf(SpamReason.DUST), spam.reasons(dust, emptySet()))
        assertTrue(spam.reasons(oneMillionth, emptySet()).isEmpty())
    }

    @Test
    fun aLookalikeSenderIsFlaggedWhenItSendsAToken() {
        assertEquals(setOf(SpamReason.LOOKALIKE), spam.reasons(token(from = lookalike), setOf(friend)))
    }

    @Test
    fun theKnownAddressItselfIsNotALookalike() {
        assertTrue(spam.reasons(token(from = friend), setOf(friend)).isEmpty())
    }

    @Test
    fun aLookalikeIsNotFlaggedForATransferTheAccountSends() {
        assertFalse(spam.reasons(token(from = lookalike, direction = TxDirection.OUT), setOf(friend)).contains(SpamReason.LOOKALIKE))
    }

    @Test
    fun aNameWithALinkOrAClaimOrAVisitIsFlagged() {
        assertEquals(setOf(SpamReason.NAME), spam.reasons(token(name = "Visit uniswap.org to claim", symbol = "FREE"), emptySet()))
        assertEquals(setOf(SpamReason.NAME), spam.reasons(token(name = "Gift", symbol = "claim-now.io"), emptySet()))
        assertTrue(spam.reasons(token(name = "USD Coin", symbol = "USDC"), emptySet()).isEmpty())
    }

    @Test
    fun anOrdinaryTransferOfAnUnlistedTokenIsNotSpam() {
        assertFalse(spam.isSpam(token(), setOf(friend)))
    }
}
