package app.lumio.ui.kit

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.VerbatimTtsAnnotation
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.chains.ChainHoldings
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** An unlisted Solana mint that titles a row is read verbatim, group by group, as an address is (#1692, spec 11.1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MintNameSpokenTest {
    @get:Rule
    val compose = createComposeRule()

    private val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    @Test
    fun aMintRowSaysItsGroupsVerbatimWithToForTheEllipsis() {
        val name = ChainHoldings.mintName(mint)
        compose.setContent { ClearAirTheme { ClearAirRow(title = name, description = "Solana Devnet") } }

        val spoken = compose.onNodeWithText("EPjF, Wdd5, to ZwyT, Dt1v", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].first()
        assertEquals("EPjF, Wdd5, to ZwyT, Dt1v", spoken.text)
        assertEquals(listOf("EPjF", "Wdd5", "ZwyT", "Dt1v"), spoken.getTtsAnnotations(0, spoken.length).map { (it.item as VerbatimTtsAnnotation).verbatim })
    }

    @Test
    fun aListedSymbolIsReadAsWritten() {
        assertNull(spokenMintName("USDC"))
        assertEquals(AnnotatedString("USDC").text, "USDC")
    }
}
