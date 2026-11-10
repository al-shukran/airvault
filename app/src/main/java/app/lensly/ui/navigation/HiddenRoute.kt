package app.lensly.ui.navigation

/**
 * Routes inside the hidden graph, whose own route is [LenslyRoute.HIDDEN]. Wallet ids are UUIDs, so the routes
 * with a `{walletId}` argument cannot match the fixed ones.
 */
internal object HiddenRoute {
    // Gate: decides between create and unlock, and shows the neutral screen when the vault cannot open.
    const val GATE = "gate"
    const val CREATE_PASSWORD = "create-password"
    const val UNLOCK = "unlock"
    const val UNAVAILABLE = "unavailable"

    // Wallets.
    const val HOME = "home"
    const val DETAIL = "detail/{walletId}"
    const val BULK = "bulk/{walletId}"
    const val EXPORT = "export/{walletId}"
    const val SCAN = "scan/{walletId}"

    // Catalog: installs a signed registry package from removable storage.
    const val CATALOG = "catalog"

    // Settings: the Bulk Cap, the catalog summary and the About notices. Reached from the wallet list.
    const val SETTINGS = "settings"
    const val CHANGE_PASSWORD = "settings/password"

    // Create and restore flows. The passphrase and name routes are shared by both.
    const val WALLET_START = "wallet/start"
    const val CEREMONY_CHOICE = "wallet/choice"
    const val DICE = "wallet/dice"
    const val COINS = "wallet/coins"
    const val SKIP = "wallet/skip"
    const val RESULT = "wallet/result"
    const val WORDS = "wallet/words"
    const val CONFIRM = "wallet/confirm"
    const val RESTORE = "wallet/restore"
    const val PASSPHRASE = "wallet/passphrase"
    const val NAME = "wallet/name"

    // Single Key import: the typed key with its address check, then the write-down that ends the flow.
    const val IMPORT_KEY = "wallet/import-key"
    const val KEY_BACKUP = "wallet/key-backup"

    const val WALLET_ID_ARG = "walletId"

    fun detail(walletId: String): String = "detail/$walletId"

    fun bulk(walletId: String): String = "bulk/$walletId"

    fun export(walletId: String): String = "export/$walletId"

    fun scan(walletId: String): String = "scan/$walletId"
}
