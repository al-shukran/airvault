package app.airvault.core.model

/**
 * What an app may do beyond signing its own paths (spec 2.3). Every capability is off by default and needs a written
 * reason in the manifest.
 */
enum class Capability {
    RAW_PRIVATE_KEY,
    MESSAGE_SIGNING,
    TYPED_DATA_SIGNING,
    BLIND_SIGNING,
    WATCH_ONLY_EXPORT,
    MULTI_SCRIPT_ACCOUNTS,
}
