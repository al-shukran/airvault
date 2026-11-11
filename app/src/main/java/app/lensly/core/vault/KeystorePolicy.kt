package app.lensly.core.vault

/** What the vault may do with the Keystore this device provides. */
internal enum class KeystoreVerdict {
    /** The vault key sits in a hardware-backed Keystore. The vault may be created. */
    HardwareBacked,

    /** The Keystore is software-only. Only a debug build may create the vault, so the emulator can run the app. */
    SoftwareInDebugOnly,

    /** The Keystore is software-only in a release build. The vault is not created. */
    Refused,
}

/**
 * The rule for creating a vault. The vault key must sit in a hardware-backed Keystore. Debug builds may use software
 * keys, so the emulator keeps working. This is a pure function, so the rule is unit-tested without an Android Keystore.
 */
internal object KeystorePolicy {
    fun verdict(hardwareBacked: Boolean, isDebugBuild: Boolean): KeystoreVerdict = when {
        hardwareBacked -> KeystoreVerdict.HardwareBacked
        isDebugBuild -> KeystoreVerdict.SoftwareInDebugOnly
        else -> KeystoreVerdict.Refused
    }
}
