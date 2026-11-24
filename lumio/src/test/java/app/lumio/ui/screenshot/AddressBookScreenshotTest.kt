package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.pair.ADDRESS_BOOK_ALICE
import app.lumio.pair.ADDRESS_BOOK_COLD
import app.lumio.pair.ADDRESS_BOOK_FAUCET
import app.lumio.ui.settings.AddressBookScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the read-only Address Book: a wallet's entries grouped by their Group, and the book of a wallet that has none. They use the same
 * theme, size and recording as [LumioScreenshotTest], and they are recorded with `:lumio:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressBookScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addressBook() = compose.lumioSnapshot("address_book") {
        AddressBookScreen(walletName = "Demo", entries = listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD, ADDRESS_BOOK_FAUCET), offline = null, onBack = {}, onLock = {})
    }

    @Test
    fun addressBookEmpty() = compose.lumioSnapshot("address_book_empty") {
        AddressBookScreen(walletName = "Demo", entries = emptyList(), offline = null, onBack = {}, onLock = {})
    }
}
