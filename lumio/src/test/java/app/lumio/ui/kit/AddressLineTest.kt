package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.web3j.crypto.Keys

/**
 * The address a row shows (#1692): the groups of four of `AddressGroups.ends` for every chain, an EVM address in its checksum case, joined by no-break
 * spaces so a line breaks only after the ellipsis, and spoken group by group.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressLineTest {
    @get:Rule
    val compose = createComposeRule()

    private val lower = "0xf278cf59f5d0e2a6d0c3c2f4a9d2a3d9f25c1cdb"
    private val checksum = Keys.toChecksumAddress(lower)

    private fun plain(text: String): String = text.replace(' ', ' ')

    @Test
    fun anEvmAddressIsTheTwoFirstAndTwoLastGroupsInChecksumCase() {
        val expected = "${checksum.substring(0, 6)} ${checksum.substring(6, 10)} … ${checksum.substring(34, 38)} ${checksum.substring(38)}"

        assertEquals(expected, plain(addressLineText(lower)))
        assertEquals(expected, plain(addressLineText(checksum)), "the same whatever case it was stored in")
        assertTrue(expected != expected.lowercase(), "a checksum has capitals")
    }

    @Test
    fun theLineBreaksOnlyAfterTheEllipsis() {
        val text = addressLineText(lower)

        assertEquals(1, text.count { it == ' ' }, "one ordinary space")
        assertTrue(text.contains("… "), "and it follows the ellipsis")
    }

    @Test
    fun aBitcoinSolanaAndAlgorandAddressAreGroupedEndsNotSixAndFour() {
        assertEquals("bc1q ar0s … wf5m dq", plain(addressLineText("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")))
        assertEquals("EPjF Wdd5 … ZwyT Dt1v", plain(addressLineText("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")))
        assertEquals("VCMJ KWOY … UYBI JA", plain(addressLineText("VCMJKWOY5P5P7SKMZFFOCEROPJCZOTIJMNIYNUCKH7LRO45JMJP6UYBIJA")))
    }

    @Test
    fun aHashIsGroupedEndsInItsOwnCase() {
        val hash = "0x" + "32".repeat(32)

        assertEquals("0x3232 3232 … 3232 3232", plain(hashLineText(hash)))
        assertEquals("0xAbCd 3232 … 3232 3232", plain(hashLineText("0xAbCd" + hash.drop(6))), "a hash is never put in checksum case")
    }

    @Test
    fun anAddressIsSpokenGroupByGroupWithToForTheEllipsis() {
        val spoken = spokenAddress(checksum)

        val groups = listOf(checksum.substring(0, 6), checksum.substring(6, 10), checksum.substring(34, 38), checksum.substring(38))
        assertEquals("address ${groups[0]}, ${groups[1]}, to ${groups[2]}, ${groups[3]}", spoken.text)
        assertEquals(groups, spoken.getTtsAnnotations(0, spoken.length).map { (it.item as VerbatimTtsAnnotation).verbatim })
    }

    @Test
    fun aHashIsSpokenTheSameWayWithoutALead() {
        assertEquals("0x3232, 3232, to 3232, 3232", spokenHash("0x" + "32".repeat(32)).text)
    }

    @Test
    fun theRowSaysTheAddressVerbatimAndPutsItAfterTheWord() {
        compose.setContent { ClearAirTheme { AddressLine(address = lower, word = "From") } }

        val node = compose.onNodeWithTag(ADDRESS_LINE_TAG).fetchSemanticsNode()
        assertEquals(spokenAddress(lower).text, node.config[SemanticsProperties.Text].first().text)
        assertTrue(compose.onNodeWithText("From").getBoundsInRoot().right <= compose.onNodeWithTag(ADDRESS_LINE_TAG).getBoundsInRoot().left)
    }

    @Test
    fun aNarrowLineBreaksAfterTheEllipsisAndItsSecondLineStartsUnderTheFirst() {
        compose.setContent { ClearAirTheme { Box(Modifier.width(170.dp)) { AddressLine(address = lower, word = "From") } } }

        val word = compose.onNodeWithText("From").getBoundsInRoot()
        val line = compose.onNodeWithTag(ADDRESS_LINE_TAG).getBoundsInRoot()
        assertTrue(line.bottom - line.top > 30.dp, "two lines of 20 dp: ${line.bottom - line.top}")
        assertTrue(line.left >= word.right, "both lines of the address start after the word, not under it")
        assertFalse(line.right > 170.dp, "nothing runs past the row")
    }
}
