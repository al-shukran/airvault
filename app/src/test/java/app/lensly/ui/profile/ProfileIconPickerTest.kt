package app.lensly.ui.profile

import app.lensly.app.ProfileIcon
import app.lensly.ui.components.profileIconSections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The icon picker: every icon sits in one labelled section, the sections keep the order of the set, and every glyph draws. */
class ProfileIconPickerTest {

    @Test
    fun theSectionsHoldEveryIconOnceAndInTheOrderOfTheSet() {
        val shown = profileIconSections.flatMap { it.icons }

        assertEquals(ProfileIcon.entries, shown)
        assertEquals(ProfileIcon.entries.size, shown.toSet().size)
    }

    @Test
    fun theSectionsAreLabelledAndNoLongerThanOneGridOfSixty() {
        assertTrue(profileIconSections.size > 1, "A long set is grouped, and this one is long.")
        val labels = profileIconSections.map { it.label }
        labels.forEach { label ->
            assertTrue(label.isNotBlank(), "Every section has a label.")
            assertTrue(label == label.trim(), "A label carries no padding: $label")
        }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun everyIconHasAGlyphOfItsOwn() {
        val glyphs = ProfileIcon.entries.map { it.glyph() }

        assertEquals(ProfileIcon.entries.size, glyphs.map { it.name }.toSet().size)
        glyphs.forEach { glyph ->
            assertEquals(24f, glyph.defaultWidth.value)
            assertTrue(glyph.name.isNotBlank(), "Every glyph is named, so a failure names it.")
        }
    }
}
