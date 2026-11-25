package app.lumio.evm

import app.airvault.chains.evm.CALLDATA_HASH_LABEL
import app.airvault.chains.evm.calldataHash
import app.airvault.chains.evm.signer.showsCalldataHash
import app.lensly.core.evm.EvmFixtures
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import app.lensly.ui.components.groupAddress
import app.lumio.reviewRows
import app.lumio.tokens.Token
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/**
 * The calldata hash on the Review page. Lensly shows the Keccak-256 of a call's calldata only for a call it cannot read, computed with web3j's
 * Hash.sha3. Lumio shows the row under the same condition, and its hash must be that same string for the same calldata.
 */
class CalldataHashRowTest {
    private val chain = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = "https://etherscan.io")
    private val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val from = AccountAddresses.addressAt(HdWallet(Mnemonic.toSeed(EvmFixtures.artMnemonic)).accountXpub(), 0)

    private fun send(token: Token?) = PreparedSend(
        chain = chain,
        index = 0,
        from = from,
        to = RECIPIENT,
        valueWei = BigInteger.valueOf(12_500_000),
        nonce = 7,
        gasLimit = BigInteger.valueOf(65_000),
        maxFeePerGas = BigInteger.valueOf(3_500_000_000L),
        maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
        balanceWei = BigInteger.TEN.pow(18),
        token = token,
        tokenBalance = BigInteger.TEN.pow(12),
    )

    @Test
    fun aPlainTransferHasNoCalldataHashRowBecauseLenslyReadsIt() {
        val send = send(usdc)
        val calldata = Numeric.hexStringToByteArray(send.tx.data)

        assertTrue(send.tx.data.startsWith("0xa9059cbb"), "The token send calls transfer(address,uint256).")
        assertFalse(showsCalldataHash(calldata))
        assertNull(reviewRows(send, "PulsarNova").singleOrNull { it.label == CALLDATA_HASH_LABEL })
    }

    @Test
    fun aCallLenslyCannotReadShowsTheKeccakOfItsCalldataAsLenslyComputesIt() {
        val calldata = Numeric.hexStringToByteArray("0x12345678" + "00".repeat(32))

        assertTrue(showsCalldataHash(calldata))
        assertEquals("0x" + Hash.sha3(calldata).toHex(), calldataHash(calldata))
    }

    @Test
    fun aNativeSendHasNoCalldataAndNoCalldataHashRow() {
        val rows = reviewRows(send(null), "PulsarNova")

        assertFalse(showsCalldataHash(Numeric.hexStringToByteArray(send(null).tx.data)))
        assertNull(rows.singleOrNull { it.label == CALLDATA_HASH_LABEL })
        assertEquals(listOf("Amount", "Max Fee", "Total at Most", "Network", "From"), rows.map { it.label })
        assertEquals("\u2068PulsarNova\u2069 (${groupAddress(from)})", rows.single { it.label == "From" }.value)
    }

    private companion object {
        const val RECIPIENT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    }
}
