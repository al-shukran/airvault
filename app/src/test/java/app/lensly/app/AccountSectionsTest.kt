package app.lensly.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The order of the detail screen: groups in name order, the accounts in no group last, and no headers when nothing is grouped. */
class AccountSectionsTest {

    @Test
    fun withoutAnyGroupThereIsOneUnnamedSectionInIndexOrder() {
        assertEquals(listOf(AccountSection(null, listOf(0, 1, 2, 3))), accountSections(listOf(3, 0, 2, 1)) { null })
    }

    @Test
    fun groupsComeInNameOrderIgnoringCaseAndTheUngroupedAccountsLast() {
        val groups = mapOf(0 to "work", 2 to "Alpha", 3 to "work", 4 to "beta")

        val sections = accountSections((0 until 6).toList()) { groups[it] }

        assertEquals(
            listOf(
                AccountSection("Alpha", listOf(2)),
                AccountSection("beta", listOf(4)),
                AccountSection("work", listOf(0, 3)),
                AccountSection(null, listOf(1, 5)),
            ),
            sections,
        )
    }

    @Test
    fun aSingleGroupWithEveryAccountInItHasNoUngroupedSection() {
        val sections = accountSections(listOf(0, 1)) { "Home" }

        assertEquals(listOf(AccountSection("Home", listOf(0, 1))), sections)
    }

    @Test
    fun groupNamesThatDifferOnlyInCaseAreTwoGroupsInAStableOrder() {
        val sections = accountSections(listOf(0, 1)) { if (it == 0) "work" else "Work" }

        assertEquals(listOf(AccountSection("Work", listOf(1)), AccountSection("work", listOf(0))), sections)
    }

    @Test
    fun aDuplicateIndexCountsOnceAndAnEmptySelectionHasNoSections() {
        assertEquals(listOf(AccountSection(null, listOf(0, 1))), accountSections(listOf(1, 0, 1)) { null })
        assertTrue(accountSections(emptyList()) { "Home" }.isEmpty())
    }
}
