package app.lumio.ui.firstrun

/**
 * The first-run screens in the order the owner sees them, I1 to I10. [DuressPassword] is the page that Set Up Now on I8 opens, before I9. [route] is the screen's name in the hidden graph. [Pair] is the
 * scan of Lensly's code (I10), above Home: the review and the import (I11 and I12) follow it.
 */
enum class FirstRunStep(val route: String) {
    Welcome("first-run/welcome"),
    Keys("first-run/keys"),
    NeverShare("first-run/never-share"),
    ProvidedAsIs("first-run/provided-as-is"),
    Notice("first-run/notice"),
    PrepareLensly("first-run/prepare-lensly"),
    CreatePassword("first-run/create-password"),
    Duress("first-run/duress"),
    DuressPassword("first-run/duress-password"),
    DeviceProtection("first-run/device-protection"),
    Pair("first-run/pair"),
}

/**
 * One segment of the chapter bar: nothing yet, or how much of it is filled. [fraction] is the share of the segment in ink. The mock fills 40 percent of
 * Understand while the owner reads its statements, and Protect 15, 40 and 80 percent on its three screens.
 */
enum class ChapterFill(val fraction: Float) { Empty(0f), Early(0.15f), Started(0.4f), Late(0.8f), Full(1f) }

/**
 * The three segments of the chapter bar for [step]: Understand, Protect and Pair. Understand is Full from the Important Notice on, so preparing
 * Lensly (I6) leaves Protect empty, and Protect fills as the mock draws it across its screens. Pair is empty until the scan (I10), where Protect is
 * full and Pair is at 40 percent.
 */
fun chapterFills(step: FirstRunStep): List<ChapterFill> = when (step) {
    FirstRunStep.Welcome, FirstRunStep.Keys, FirstRunStep.NeverShare, FirstRunStep.ProvidedAsIs -> listOf(ChapterFill.Started, ChapterFill.Empty, ChapterFill.Empty)
    FirstRunStep.Notice, FirstRunStep.PrepareLensly -> listOf(ChapterFill.Full, ChapterFill.Empty, ChapterFill.Empty)
    FirstRunStep.CreatePassword -> listOf(ChapterFill.Full, ChapterFill.Early, ChapterFill.Empty)
    FirstRunStep.Duress, FirstRunStep.DuressPassword -> listOf(ChapterFill.Full, ChapterFill.Started, ChapterFill.Empty)
    FirstRunStep.DeviceProtection -> listOf(ChapterFill.Full, ChapterFill.Late, ChapterFill.Empty)
    FirstRunStep.Pair -> listOf(ChapterFill.Full, ChapterFill.Full, ChapterFill.Started)
}
