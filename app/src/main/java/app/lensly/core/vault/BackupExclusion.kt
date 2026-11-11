package app.lensly.core.vault

import android.content.pm.ApplicationInfo

/**
 * Backup Exclusion. The packaged manifest sets allowBackup to false, and the backup rules exclude the vault's files, so no
 * cloud backup or device transfer carries the vault. A build whose manifest allows backup could move the vault off the device,
 * so it is refused before a vault is created. An existing vault is not touched.
 */
internal object BackupExclusion {
    /** True when the packaged app lets Android back up or transfer its data, read from [ApplicationInfo.flags]. */
    fun allowsBackup(flags: Int): Boolean = flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0

    /**
     * The error for a build that allows backup. It names the setting and holds no secret. The startup check logs it, and the
     * screen that refuses provisioning shows it.
     */
    const val REFUSAL = "This build allows Android to back up its data, so Lensly does not create a vault in it. " +
        "A release must set android:allowBackup to false."

    const val LOG_TAG = "Lensly"
}
