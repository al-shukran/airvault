package app.lensly.core.vault

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces [target] with [bytes] using a synced temp file and an atomic rename, so that a reader sees
 * either the old file or the new one, never a partial write.
 */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
    Files.move(
        temp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
    )
}
