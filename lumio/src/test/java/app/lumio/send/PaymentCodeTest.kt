package app.lumio.send

import app.lensly.core.btc.BtcNetwork
import app.lumio.evm.EvmChain
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

/** What a scanned code fills in: EIP-681 on the Ethereum send and BIP21 on the Bitcoin send, and the words for a code that is refused. */
class PaymentCodeTest {
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, testnet = true, explorerUrl = null)
    private val btcTestnet = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    private val btcMainnet = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    private fun evm(text: String): ScannedPayment = EvmPaymentCode.read(text, sepolia)

    private fun btc(text: String, network: BtcNetwork): ScannedPayment = BtcPaymentCode.read(text, network)

    /** The words of the refusal that [block] raises. */
    private fun refusal(block: () -> Any?): String = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()

    @Test
    fun aPlainEthereumAddressFillsTheRecipientWithNoAmount() {
        val payment = evm("  $RECIPIENT \n")
        assertEquals(RECIPIENT, payment.address)
        assertNull(payment.amount)
    }

    @Test
    fun anEip681RequestWithAChainAndAValueGivesTheAmountInTheChainsUnits() {
        val payment = evm("ethereum:$RECIPIENT@11155111?value=10000000000000000")
        assertEquals(RECIPIENT, payment.address)
        assertEquals("0.01", payment.amount)
    }

    @Test
    fun anEip681ValueInScientificNotationIsWholeWei() {
        assertEquals("0.01", evm("ethereum:$RECIPIENT?value=1e16").amount)
        assertEquals("0.25", evm("ethereum:$RECIPIENT@11155111?value=2.5e17").amount)
    }

    @Test
    fun anEip681RequestWithoutAChainOrAValueIsAPlainPayment() {
        val payment = evm("ethereum:$RECIPIENT")
        assertEquals(RECIPIENT, payment.address)
        assertNull(payment.amount)
    }

    @Test
    fun anEip681ZeroValueNamesNoAmount() {
        assertNull(evm("ethereum:$RECIPIENT?value=0").amount)
    }

    @Test
    fun anEip681ValueWithAnExponentOfMillionsIsRefusedWithoutBeingExpanded() {
        assertEquals(TOO_LARGE_WORDS, refusal { evm("ethereum:$RECIPIENT?value=1e99999999") })
        assertEquals(TOO_LARGE_WORDS, refusal { evm("ethereum:$RECIPIENT?value=1e99999999999") })
        assertEquals(TOO_LARGE_WORDS, refusal { evm("ethereum:$RECIPIENT?value=1e78") })
    }

    @Test
    fun anEip681RequestForAnotherChainIsRefusedWithTheChainItIsFor() {
        assertEquals(
            "This code is for chain 1, but Sepolia is selected. Choose that network to scan it.",
            refusal { evm("ethereum:$RECIPIENT@1?value=1e16") },
        )
    }

    @Test
    fun anEip681RequestForAnotherChainThatIsOnNamesThatChainAndReadsItsAmountInItsUnits() {
        val base = EvmChain(8453L, "Base", "ETH", 18, testnet = false, explorerUrl = null)
        val monad = EvmChain(10143L, "Monad Testnet", "MON", 6, testnet = true, explorerUrl = null)

        val payment = EvmPaymentCode.read("ethereum:$RECIPIENT@10143?value=1e6", sepolia, others = listOf(base, monad))

        assertEquals(10143L, payment.chainId)
        assertEquals("1", payment.amount)
        assertNull(EvmPaymentCode.read("ethereum:$RECIPIENT@11155111", sepolia, others = listOf(base, monad)).chainId)
        assertNull(EvmPaymentCode.read(RECIPIENT, sepolia, others = listOf(base)).chainId)
        assertEquals(
            "This code is for chain 1, but Sepolia is selected. Choose that network to scan it.",
            refusal { EvmPaymentCode.read("ethereum:$RECIPIENT@1", sepolia, others = listOf(base, monad)) },
        )
    }

    @Test
    fun theSchemeIsReadWhateverItsCase() {
        assertEquals("0.01", evm("ETHEREUM:$RECIPIENT@11155111?value=1e16").amount)
    }

    @Test
    fun otherParametersOfAnEip681RequestAreIgnored() {
        assertEquals("0.01", evm("ethereum:$RECIPIENT?label=Shop&value=1e16&gas=21000").amount)
    }

    @Test
    fun aCallToAContractIsRefusedBecauseLumioSendsOnlyTransfers() {
        assertEquals(
            "This code calls a contract. Lumio sends only transfers.",
            refusal { evm("ethereum:$RECIPIENT/transfer?address=$RECIPIENT") },
        )
    }

    @Test
    fun aChainThatIsNotANumberIsRefused() {
        assertEquals("This code names a chain that is not a number.", refusal { evm("ethereum:$RECIPIENT@sepolia") })
    }

    @Test
    fun aValueThatIsNotAWholeNumberOfWeiOrNotAnAmountIsRefused() {
        assertEquals("The value in this code is not a whole number of wei.", refusal { evm("ethereum:$RECIPIENT?value=0.5") })
        assertEquals("The value in this code is not an amount.", refusal { evm("ethereum:$RECIPIENT?value=abc") })
    }

    @Test
    fun anAddressInOneCaseInAPaymentCodeHasNoChecksumAndIsRefused() {
        assertEquals(
            "This address has no checksum. Enter it with its EIP-55 capital letters.",
            refusal { evm("ethereum:${RECIPIENT.lowercase()}?value=1e16") },
        )
    }

    @Test
    fun aBitcoinCodeIsRefusedOnTheEthereumSend() {
        assertEquals("This is a Bitcoin payment code. Use Send Bitcoin for it.", refusal { evm("bitcoin:$btcTestnet") })
    }

    @Test
    fun textThatIsNotAnEthereumCodeIsRefused() {
        assertEquals("This code is not an Ethereum address or payment request.", refusal { evm("https://example.com") })
    }

    @Test
    fun aPlainBitcoinAddressFillsTheRecipientOfItsNetworkWithNoAmount() {
        val payment = btc(" $btcTestnet ", BtcNetwork.TESTNET4)
        assertEquals(btcTestnet, payment.address)
        assertNull(payment.amount)
    }

    @Test
    fun aBip21RequestGivesTheAmountInBitcoinAndIgnoresTheLabel() {
        val payment = btc("bitcoin:$btcTestnet?amount=0.0001&label=Shop", BtcNetwork.TESTNET4)
        assertEquals(btcTestnet, payment.address)
        assertEquals("0.0001", payment.amount)
    }

    @Test
    fun aBip21SchemeIsReadWhateverItsCaseAndAnAddressWithNoAmountHasNone() {
        assertEquals("0.001", btc("BITCOIN:$btcTestnet?amount=0.001", BtcNetwork.TESTNET4).amount)
        assertNull(btc("bitcoin:$btcTestnet", BtcNetwork.TESTNET4).amount)
    }

    @Test
    fun aMainnetAddressOnTestnet4IsRefusedWithTheNetworkItIsFor() {
        assertEquals(
            "This address is for mainnet, but testnet4 is selected. Choose that network to scan it.",
            refusal { btc(btcMainnet, BtcNetwork.TESTNET4) },
        )
        assertEquals(
            "This address is for mainnet, but testnet4 is selected. Choose that network to scan it.",
            refusal { btc("bitcoin:$btcMainnet?amount=0.001", BtcNetwork.TESTNET4) },
        )
    }

    @Test
    fun aTestnetAddressOnMainnetIsRefusedWithTheNetworkItIsFor() {
        assertEquals(
            "This address is for testnet4, but mainnet is selected. Choose that network to scan it.",
            refusal { btc(btcTestnet, BtcNetwork.MAINNET) },
        )
    }

    @Test
    fun aBip21AmountIsCheckedAsAnAmountOfBitcoin() {
        assertEquals("Use at most 8 decimal places for BTC.", refusal { btc("bitcoin:$btcTestnet?amount=0.000000001", BtcNetwork.TESTNET4) })
        assertEquals(
            "The amount must be at least 546 sat, the dust limit.",
            refusal { btc("bitcoin:$btcTestnet?amount=0.0000001", BtcNetwork.TESTNET4) },
        )
    }

    @Test
    fun aCodeThatIsNoBitcoinAddressIsRefusedAndAnEthereumAddressIsNotOneEither() {
        assertEquals("This code is not a Bitcoin address or payment request.", refusal { btc("https://example.com", BtcNetwork.TESTNET4) })
        assertEquals("This code is not a Bitcoin address or payment request.", refusal { btc(RECIPIENT, BtcNetwork.TESTNET4) })
    }

    private companion object {
        const val TOO_LARGE_WORDS = "The value in this code is too large for an amount."
        const val RECIPIENT = "0x52908400098527886E0F7030069857D2E4169EE7"
    }
}
