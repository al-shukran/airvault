package app.lensly.app

import app.airvault.core.model.AddressGroups
import app.lensly.ui.components.groupAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/** The summary sentence and the step lines of a contract call write an address the same way, because both come from [AddressGroups]. */
class AddressGroupsParityTest {
    private val address = "0x1593D3988b3a1C3b1f4F1eB1b0d0e0aC3eA25343"

    @Test
    fun theSummaryAndTheScreensUseTheRuleTheStepsUse() {
        assertEquals(AddressGroups.ends(address), addressEnds(address))
        assertEquals(AddressGroups.group(address), groupAddress(address))
    }
}
