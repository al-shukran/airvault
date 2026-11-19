package app.lumio.evm

import app.airvault.chains.evm.signer.ContractCall
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.UnsignedTx
import app.lensly.core.evm.EvmFixtures
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lumio.tokens.Erc20
import app.lumio.tokens.Token
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.Test
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** A token send: the transaction it signs, what Lensly reads back from it, the amount in the token's units, and the native cost of its gas. */
class TokenSendTest {
    private val chain = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = "https://etherscan.io")
    private val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val dai = Token(1L, "0x6b175474e89094c44da98b954eedeac495271d0f", "DAI", "Dai Stablecoin", 18)
    private val from = AccountAddresses.addressAt(HdWallet(Mnemonic.toSeed(EvmFixtures.artMnemonic)).accountXpub(), 0)

    /** A token send of [amount] base units of [token] to [RECIPIENT], with the native balance and gas of the send. */
    private fun tokenSend(amount: BigInteger, token: Token = usdc) = PreparedSend(
        chain = chain,
        index = 0,
        from = from,
        to = RECIPIENT,
        valueWei = amount,
        nonce = 7,
        gasLimit = BigInteger.valueOf(65_000),
        maxFeePerGas = BigInteger.valueOf(3_500_000_000L),
        maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
        balanceWei = BigInteger.TEN.pow(18),
        token = token,
        tokenBalance = BigInteger.TEN.pow(12),
    )

    @Test
    fun aTokenSendCallsTheTokenContractWithTheTransferAndNoValue() {
        val send = tokenSend(BigInteger.valueOf(12_500_000))

        assertEquals(Keys.toChecksumAddress(usdc.contract), send.tx.to)
        assertEquals(BigInteger.ZERO, send.tx.value)
        assertEquals(Erc20.transfer(RECIPIENT, BigInteger.valueOf(12_500_000)), send.tx.data)
    }

    @Test
    fun theTokenTransferIsReadBackByLenslyAsTheSameTransferOfTheSameAmount() {
        val send = tokenSend(BigInteger.valueOf(12_500_000))

        val decoded = assertIs<UnsignedTx.Result.Ok>(UnsignedTx.decode(send.payload, DataType.TYPED_TRANSACTION)).tx
        val call = assertIs<ContractCall.Transfer>(ContractCall.decode(Numeric.hexStringToByteArray(decoded.data)))

        assertEquals(Keys.toChecksumAddress(usdc.contract), decoded.to)
        assertEquals(BigInteger.ZERO, decoded.value)
        assertEquals(RECIPIENT.lowercase(), call.to)
        assertEquals(BigInteger.valueOf(12_500_000), call.value)
    }

    @Test
    fun aNativeSendHasNoDataAndCarriesItsAmount() {
        val send = PreparedSend(
            chain, 0, from, RECIPIENT, BigInteger.TEN.pow(16), 7, BigInteger.valueOf(21_000),
            BigInteger.valueOf(3_500_000_000L), BigInteger.valueOf(1_500_000_000L), BigInteger.TEN.pow(18),
        )

        assertEquals("0x", send.tx.data)
        assertEquals(BigInteger.TEN.pow(16), send.tx.value)
        assertEquals(RECIPIENT, send.tx.to)
    }

    @Test
    fun aTokenSendCostsOnlyGasInTheNativeCoin() {
        val send = tokenSend(BigInteger.valueOf(12_500_000))

        assertEquals(send.maxFeeWei, send.maxCostWei)
        assertEquals(BigInteger.valueOf(65_000) * BigInteger.valueOf(3_500_000_000L), send.maxFeeWei)
    }

    @Test
    fun aNativeSendCostsItsAmountAndItsGas() {
        val amount = BigInteger.TEN.pow(16)
        val send = PreparedSend(
            chain, 0, from, RECIPIENT, amount, 7, BigInteger.valueOf(21_000),
            BigInteger.valueOf(3_500_000_000L), BigInteger.valueOf(1_500_000_000L), BigInteger.TEN.pow(18),
        )

        assertEquals(amount + send.maxFeeWei, send.maxCostWei)
    }

    @Test
    fun aSixDecimalTokenAmountIsScaledToItsBaseUnits() {
        assertEquals(BigInteger.valueOf(12_500_000), SendInput.amountWei("12.5", usdc.decimals, usdc.symbol))
        assertEquals(BigInteger.valueOf(1_000_000), SendInput.amountWei("1", usdc.decimals, usdc.symbol))
        assertEquals(BigInteger.ONE, SendInput.amountWei("0.000001", usdc.decimals, usdc.symbol))
        assertEquals(BigInteger.valueOf(1_000_000), SendInput.amountWei("1.0000000", usdc.decimals, usdc.symbol))
    }

    @Test
    fun an18DecimalTokenAmountIsScaledToItsBaseUnits() {
        assertEquals(BigInteger("1500000000000000000"), SendInput.amountWei("1.5", dai.decimals, dai.symbol))
        assertEquals(BigInteger.ONE, SendInput.amountWei("0.000000000000000001", dai.decimals, dai.symbol))
    }

    @Test
    fun anAmountWithMoreDecimalsThanTheTokenHasIsRefusedNotRounded() {
        val sixDecimals = assertFailsWith<IllegalArgumentException> { SendInput.amountWei("0.0000001", usdc.decimals, usdc.symbol) }
        val eighteenDecimals = assertFailsWith<IllegalArgumentException> {
            SendInput.amountWei("0.0000000000000000001", dai.decimals, dai.symbol)
        }

        assertEquals("Use at most 6 decimal places for USDC.", sixDecimals.message)
        assertEquals("Use at most 18 decimal places for DAI.", eighteenDecimals.message)
    }

    private companion object {
        /** The EIP-55 example address of the standard. Its checksum is valid. */
        const val RECIPIENT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    }
}
