package app.lensly.core.registry

import kotlinx.serialization.Serializable

/**
 * One online wallet in the Wallet Compatibility Registry. [name] identifies it. [formats] are the code formats the vault reads and
 * writes for it, and [ecosystems] are the chains it signs for. [note] is the support statement and [limitations] the known limits;
 * the pairing wizard shows both before the owner pairs. It is a support statement, never a condition for signing: a wallet absent
 * from the registry is refused for its format, not for its name.
 */
@Serializable
data class WalletEntry(
    val name: String,
    val ecosystems: List<String>,
    val formats: List<String>,
    val note: String,
    val limitations: String,
) {
    init {
        require(isDisplayText(name, LABEL_MAX)) { "name must be 1 to $LABEL_MAX printable characters" }
        require(ecosystems.isNotEmpty() && ecosystems.all { isDisplayText(it, LABEL_MAX) }) {
            "ecosystems must name at least one, each 1 to $LABEL_MAX printable characters"
        }
        require(formats.isNotEmpty() && formats.all { isDisplayText(it, LABEL_MAX) }) {
            "formats must name at least one, each 1 to $LABEL_MAX printable characters"
        }
        require(isDisplayText(note, NOTE_MAX)) { "note must be 1 to $NOTE_MAX printable characters" }
        require(isDisplayText(limitations, NOTE_MAX)) { "limitations must be 1 to $NOTE_MAX printable characters" }
    }
}
