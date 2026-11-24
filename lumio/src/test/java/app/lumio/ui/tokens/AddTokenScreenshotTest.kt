package app.lumio.ui.tokens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.tokens.Token
import app.lumio.ui.screenshot.LUMIO_DARK_QUALIFIERS
import app.lumio.ui.screenshot.lumioSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The golden of the Add Token screen in its fullest state: a lookup has found a token, the owner has one token of their own, and the chain's default
 * tokens are listed. It is recorded with `:lumio:recordRoborazziDebug` and checked with `:lumio:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddTokenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addToken() = compose.lumioSnapshot("add_token") {
        AddTokenScreen(
            chainName = "Ethereum",
            defaults = listOf(
                Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6),
                Token(1L, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", "Tether USD", 6),
                Token(1L, "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", "WBTC", "Wrapped BTC", 8),
            ),
            custom = listOf(Token(1L, "0x6b175474e89094c44da98b954eedeac495271d0f", "DAI", "Dai Stablecoin", 18)),
            address = "0x6982508145454ce325ddbe47a25d4ec3d2311933",
            busy = false,
            found = Token(1L, "0x6982508145454ce325ddbe47a25d4ec3d2311933", "PEPE", "Pepe", 18),
            holding = TokenHolding("PulsarNova Holds", "1,200 PEPE"),
            problem = null,
            offline = null,
            onAddress = {},
            onLookUp = {},
            onAdd = {},
            onCancel = {},
            onChangeNetwork = {},
            onRemove = {},
            onBack = {},
            onLock = {},
        )
    }
}
