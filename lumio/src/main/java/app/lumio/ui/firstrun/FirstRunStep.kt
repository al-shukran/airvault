package app.lumio.ui.firstrun

/**
 * The first-run screens in the order the owner sees them, I1 to I9. [route] is the screen's name in the hidden graph. Pairing (I10 to I12)
 * follows [DeviceProtection] on the Wallets home, and is not in this list.
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
    DeviceProtection("first-run/device-protection"),
}

/** One segment of the chapter bar: nothing yet, half way through, or done. [fraction] is how much of the segment is filled. */
enum class ChapterFill(val fraction: Float) { Empty(0f), Half(0.5f), Full(1f) }

private val UNDERSTAND = FirstRunStep.Welcome.ordinal..FirstRunStep.Notice.ordinal
private val PROTECT = FirstRunStep.CreatePassword.ordinal..FirstRunStep.DeviceProtection.ordinal

/**
 * The three segments of the chapter bar for [step]: Understand, Protect and Pair. A chapter is Half while the owner is inside it and Full
 * from its last screen on. Before its first screen it is Empty, so preparing Lensly (I6) leaves Protect empty. Pair fills only once
 * scanning starts, which is after these screens, so it is always empty here.
 */
fun chapterFills(step: FirstRunStep): List<ChapterFill> = listOf(
    chapterFill(UNDERSTAND, step),
    chapterFill(PROTECT, step),
    ChapterFill.Empty,
)

private fun chapterFill(chapter: IntRange, step: FirstRunStep): ChapterFill = when {
    step.ordinal < chapter.first -> ChapterFill.Empty
    step.ordinal >= chapter.last -> ChapterFill.Full
    else -> ChapterFill.Half
}
