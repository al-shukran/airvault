package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.receive.ReceiveScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The goldens of the Receive page: an Ethereum account, and a Solana account whose SPL tokens arrive at the address it shows. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiveScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun receiveEthereum() = compose.lumioSnapshot("receive_ethereum") {
        ReceiveScreen(
            walletName = "Demo",
            detail = "Ethereum and every EVM network, address 1",
            address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            onlySend = "Only send Monad testnet assets here.",
            onBack = {},
            title = "Receive MON",
        )
    }

    @Test
    fun receiveSolanaSplNote() = compose.lumioSnapshot("receive_solana_spl_note") {
        ReceiveScreen(
            walletName = "Demo",
            detail = "Solana devnet",
            address = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
            onlySend = "Only send Solana devnet assets here.",
            onBack = {},
            title = "Receive SOL",
            note = "Tokens arrive at this same address.",
        )
    }
}
