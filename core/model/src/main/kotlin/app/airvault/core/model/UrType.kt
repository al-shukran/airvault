package app.airvault.core.model

/** Which way a QR type travels: a request into Lensly, a response out of it, or pairing (spec 7.2). */
enum class Direction { REQUEST, RESPONSE, PAIRING }

/** A QR (UR) type an app parses or emits. [versions] are the payload schema versions it accepts or emits (spec 7.2). */
data class UrTypeSpec(
    val type: String,
    val direction: Direction,
    val versions: IntRange,
)
