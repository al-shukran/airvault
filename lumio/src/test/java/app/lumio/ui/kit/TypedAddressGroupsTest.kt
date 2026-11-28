package app.lumio.ui.kit

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/** The address grouping: what the field shows for a typed address, and where the cursor lands over its spaces. */
class TypedAddressGroupsTest {

    private fun shown(raw: String): String = TypedAddressGroups.filter(AnnotatedString(raw)).text.text

    @Test
    fun aFullAddressShowsItsFirstGroupWithThe0xAndThenGroupsOfFour() {
        assertEquals("0x3a98 f1c2 7b40 d6e5", shown("0x3a98f1c27b40d6e5"))
    }

    @Test
    fun aPartialAddressGroupsWhatIsTypedAndAShortOneHasNoSpace() {
        assertEquals("0x3a98 f1", shown("0x3a98f1"))
        assertEquals("0x3a98", shown("0x3a98"))
        assertEquals("", shown(""))
    }

    @Test
    fun theCursorMapsOverTheDigitsAndSkipsTheSpaces() {
        val mapping = TypedAddressGroups.filter(AnnotatedString("0x3a98f1c27b40d6e5")).offsetMapping

        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(7, mapping.originalToTransformed(6))
        assertEquals(12, mapping.originalToTransformed(10))
        assertEquals(21, mapping.originalToTransformed(18))
        assertEquals(6, mapping.transformedToOriginal(6))
        assertEquals(6, mapping.transformedToOriginal(7))
        assertEquals(10, mapping.transformedToOriginal(12))
        assertEquals(18, mapping.transformedToOriginal(21))
    }

    @Test
    fun everyCursorPositionMapsBackToItself() {
        val raw = "0x3a98f1c27b40d6e5"
        val mapping = TypedAddressGroups.filter(AnnotatedString(raw)).offsetMapping

        (0..raw.length).forEach { offset ->
            assertEquals(offset, mapping.transformedToOriginal(mapping.originalToTransformed(offset)))
        }
    }
}
