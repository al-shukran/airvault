package app.lumio.ui.send

import app.lensly.core.btc.BtcNetwork
import app.lumio.ui.btc.btcRecipientError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The words the paste field shows for a recipient that is not an address the send can pay. A valid address shows nothing, and each
 * refusal is the one the send itself gives.
 */
class RecipientErrorTest {

    @Test
    fun aChecksummedEvmAddressShowsNoError() {
        assertNull(recipientError(EVM_CHECKSUMMED))
    }

    @Test
    fun anEvmAddressWithPaddingShowsNoErrorOnceTrimmed() {
        assertNull(recipientError("  $EVM_CHECKSUMMED \n"))
    }

    @Test
    fun anEvmAddressInOneCaseHasNoChecksumToCheck() {
        assertEquals(
            "This address has no checksum. Enter it with its EIP-55 capital letters.",
            recipientError(EVM_CHECKSUMMED.lowercase()),
        )
    }

    @Test
    fun anEvmAddressWithAWrongChecksumIsRefused() {
        assertEquals(
            "The checksum does not match. Check the recipient address.",
            recipientError("0x52908400098527886e0F7030069857D2E4169EE7"),
        )
    }

    @Test
    fun textThatIsNotAnEvmAddressIsRefused() {
        assertEquals("Enter the recipient: 0x and 40 hex characters.", recipientError("not an address"))
        assertEquals("Enter the recipient: 0x and 40 hex characters.", recipientError("0x1234"))
    }

    @Test
    fun aMainnetAddressIsAcceptedOnMainnet() {
        assertNull(btcRecipientError(BTC_MAINNET, BtcNetwork.MAINNET))
    }

    @Test
    fun aMainnetAddressIsRefusedOnTestnet4() {
        assertEquals("Enter a testnet4 address.", btcRecipientError(BTC_MAINNET, BtcNetwork.TESTNET4))
    }

    @Test
    fun aTestnetAddressIsAcceptedOnTestnet4() {
        assertNull(btcRecipientError(BTC_TESTNET, BtcNetwork.TESTNET4))
    }

    @Test
    fun aTestnetAddressIsRefusedOnMainnet() {
        assertEquals("Enter a mainnet address.", btcRecipientError(BTC_TESTNET, BtcNetwork.MAINNET))
    }

    private companion object {
        const val EVM_CHECKSUMMED = "0x52908400098527886E0F7030069857D2E4169EE7"
        const val BTC_MAINNET = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        const val BTC_TESTNET = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    }
}
