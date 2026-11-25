package app.lumio.ui.firstrun

import app.lumio.ui.firstrun.ChapterFill.Empty
import app.lumio.ui.firstrun.ChapterFill.Full
import app.lumio.ui.firstrun.ChapterFill.Started
import app.lumio.ui.firstrun.FirstRunStep.CreatePassword
import app.lumio.ui.firstrun.FirstRunStep.DeviceProtection
import app.lumio.ui.firstrun.FirstRunStep.Duress
import app.lumio.ui.firstrun.FirstRunStep.Keys
import app.lumio.ui.firstrun.FirstRunStep.Notice
import app.lumio.ui.firstrun.FirstRunStep.NeverShare
import app.lumio.ui.firstrun.FirstRunStep.PrepareLensly
import app.lumio.ui.firstrun.FirstRunStep.ProvidedAsIs
import app.lumio.ui.firstrun.FirstRunStep.Welcome
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunStepTest {

    @Test
    fun theStepsRunInTheOwnersOrder() {
        assertEquals(
            listOf(Welcome, Keys, NeverShare, ProvidedAsIs, Notice, PrepareLensly, CreatePassword, Duress, DeviceProtection),
            FirstRunStep.entries,
        )
    }

    @Test
    fun everyStepHasItsOwnRoute() {
        assertEquals(FirstRunStep.entries.size, FirstRunStep.entries.map { it.route }.toSet().size)
    }

    @Test
    fun understandFillsAsTheOwnerReadsItAndIsFullFromTheNoticeOn() {
        assertEquals(listOf(Started, Empty, Empty), chapterFills(Welcome))
        assertEquals(listOf(Started, Empty, Empty), chapterFills(Keys))
        assertEquals(listOf(Started, Empty, Empty), chapterFills(NeverShare))
        assertEquals(listOf(Started, Empty, Empty), chapterFills(ProvidedAsIs))
        assertEquals(listOf(Full, Empty, Empty), chapterFills(Notice))
    }

    @Test
    fun preparingLensleyLeavesProtectEmpty() {
        assertEquals(listOf(Full, Empty, Empty), chapterFills(PrepareLensly))
    }

    @Test
    fun protectFillsAcrossItsScreens() {
        assertEquals(listOf(Full, Started, Empty), chapterFills(CreatePassword))
        assertEquals(listOf(Full, Started, Empty), chapterFills(Duress))
        assertEquals(listOf(Full, Full, Empty), chapterFills(DeviceProtection))
    }
}
