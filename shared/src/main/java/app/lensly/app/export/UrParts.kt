package app.lensly.app.export

/** The "i-n" sequence header that every frame of a multi-part UR carries after its type, as in ur:type/1-3/body. */
private val SEQUENCE_HEADER = Regex("""^UR:[A-Z0-9-]+/\d+-(\d+)/""", RegexOption.IGNORE_CASE)

/**
 * The parts to show for one UR, taken from an endless frame sequence in core/ur. A single-part UR gives one frame.
 * A multi-part UR gives its first seqLen frames, the uncoded fragments, which the encoder emits before any mixed
 * ones. A caller that cycles this list therefore sends the whole payload in every pass.
 */
fun partsOf(frames: Sequence<String>): List<String> {
    val seqLen = SEQUENCE_HEADER.find(frames.first())?.groupValues?.get(1)?.toInt() ?: 1
    return frames.take(seqLen).toList()
}
