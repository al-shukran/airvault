package app.lumio.tokens

import app.lensly.core.registry.ContractCall
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/**
 * The transfer call data that a token send signs. Each vector is checked three ways: against the literal words, against web3j's ABI encoder,
 * and against Lensly's decoder, which reads what the review shows.
 */
class Erc20TransferTest {
    /** The EIP-55 example address, which is also the recipient of the send. */
    private val recipient = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

    @Test
    fun theSelectorIsTheFirstFourBytesOfTheHashOfTheSignature() {
        // sha3String returns 0x and 64 hex digits; the selector is the first 0x and eight of them.
        assertEquals(Erc20.TRANSFER, Hash.sha3String("transfer(address,uint256)").take(10))
    }

    @Test
    fun aTransferOf125USDCIsTheSelectorThenTheRecipientAndTheAmountAsWords() {
        // 12.5 USDC is 12,500,000 base units at six decimals: 0xbebc20.
        val expected = "0xa9059cbb" +
            "00".repeat(12) + "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed" +
            "0".repeat(58) + "bebc20"

        assertEquals(expected, Erc20.transfer(recipient, BigInteger.valueOf(12_500_000)))
    }

    @Test
    fun theCallDataAgreesWithWeb3jsAbiEncoder() {
        for (amount in listOf(BigInteger.valueOf(12_500_000), BigInteger.TEN.pow(18), BigInteger.ONE)) {
            val reference = FunctionEncoder.encode(
                Function("transfer", listOf<Type<*>>(Address(recipient), Uint256(amount)), emptyList<TypeReference<*>>()),
            )

            assertEquals(reference, Erc20.transfer(recipient, amount), "amount $amount")
        }
    }

    @Test
    fun aMaximalAmountFillsTheWordAndStillDecodes() {
        val max = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        val data = Numeric.hexStringToByteArray(Erc20.transfer(recipient, max))

        val call = assertIs<ContractCall.Transfer>(ContractCall.decode(data))
        assertEquals(max, call.value)
    }

    @Test
    fun lenslyReadsTheTransferBackAsTheSameRecipientAndAmount() {
        val data = Numeric.hexStringToByteArray(Erc20.transfer(recipient, BigInteger.valueOf(12_500_000)))

        val call = assertIs<ContractCall.Transfer>(ContractCall.decode(data))

        assertEquals(recipient.lowercase(), call.to)
        assertEquals(BigInteger.valueOf(12_500_000), call.value)
        assertEquals(Erc20.transfer(recipient, BigInteger.valueOf(12_500_000)), "0x" + call.encode()!!.toHex())
    }

    @Test
    fun anAddressThatIsNotAnAddressIsRefused() {
        val failure = runCatching { Erc20.transfer("0x1234", BigInteger.ONE) }.exceptionOrNull()

        assertEquals("Not an EVM address.", failure?.message)
    }
}
