package app.lensly.core.vault

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes [bytes] to a synced temp file beside [target] and returns it. The name is unique, so a stale or planted
 * `<name>.tmp` is neither written nor renamed over [target]. A failed write deletes the temp file.
 * Nothing at [target] changes until [commitTemp] runs.
 */
internal fun writeTemp(target: File, bytes: ByteArray): File {
    val temp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
    return temp
}

/**
 * Atomically renames [temp] over [target], so a reader sees either the old file or the new one. A failed rename
 * deletes [temp].
 */
internal fun commitTemp(temp: File, target: File) {
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
}

/** [writeTemp] followed by [commitTemp]. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    commitTemp(writeTemp(target, bytes), target)
}

/** Syncs [dir] itself, so a file created or renamed in it is still there after a power cut. */
internal fun syncDirectory(dir: File) {
    FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
