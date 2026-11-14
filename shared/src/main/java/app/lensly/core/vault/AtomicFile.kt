package app.lensly.core.vault

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [bytes] to a synced `<name>.tmp` beside [target] and returns that temp file.
 * Nothing at [target] changes until [commitTemp] runs.
 */
internal fun writeTemp(target: File, bytes: ByteArray): File {
    val temp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
    return temp
}

/** Atomically renames [temp] over [target], so a reader sees either the old file or the new one. */
internal fun commitTemp(temp: File, target: File) {
    Files.move(
        temp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
    )
}

/** [writeTemp] followed by [commitTemp]. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    commitTemp(writeTemp(target, bytes), target)
}
