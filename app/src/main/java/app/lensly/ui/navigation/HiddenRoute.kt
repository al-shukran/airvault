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
    // Export of one account: its address as a code, reached from the account row of the wallet detail.
    const val EXPORT_ACCOUNT = "export-account/{walletId}/{index}"
    const val SCAN = "scan/{walletId}"

    // Show Secret: the App Password again, a warning, then the secret. Reached from the wallet detail.
    const val SHOW_SECRET = "secret/{walletId}"
    // Delete Wallet: the App Password again, a warning with its confirmation, then the shred. Reached from the wallet detail.
    const val DELETE_WALLET = "delete/{walletId}"
    // Renames: a wallet's Display Name, one account's, and a batch of accounts'. All use the shared name screen.
    const val RENAME_WALLET = "rename-wallet/{walletId}"
    const val RENAME_ACCOUNT = "rename-account/{walletId}/{index}"
    const val RENAME_ACCOUNTS = "rename-accounts/{walletId}/{indices}"

    // Catalog: installs a signed registry package from removable storage.
    const val CATALOG = "catalog"

    // Settings: the Bulk Cap, the catalog summary and the About notices. Reached from the wallet list.
    const val SETTINGS = "settings"
    const val CHANGE_PASSWORD = "settings/password"
    // Failure Delay cap: a choice, a warning below 10 s, then the App Password. Reached from Settings.
    const val FAILURE_CAP = "settings/failure-cap"
    // Wipe: the App Password again, a warning with its confirmation, then every file. Reached from Settings.
    const val WIPE = "settings/wipe"

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
    // A wallet with a passphrase: its write-down, the last step before the wallet is saved.
    const val PASSPHRASE_BACKUP = "wallet/passphrase-backup"

    // Single Key import: the typed key with its address check, then the write-down that ends the flow.
    const val IMPORT_KEY = "wallet/import-key"
    const val KEY_BACKUP = "wallet/key-backup"

    const val WALLET_ID_ARG = "walletId"
    const val ACCOUNT_INDEX_ARG = "index"
    const val ACCOUNT_INDICES_ARG = "indices"

    fun detail(walletId: String): String = "detail/$walletId"

    fun bulk(walletId: String): String = "bulk/$walletId"

    fun export(walletId: String): String = "export/$walletId"

    fun exportAccount(walletId: String, index: Int): String = "export-account/$walletId/$index"

    fun scan(walletId: String): String = "scan/$walletId"

    fun showSecret(walletId: String): String = "secret/$walletId"

    fun deleteWallet(walletId: String): String = "delete/$walletId"
    fun renameWallet(walletId: String): String = "rename-wallet/$walletId"

    fun renameAccount(walletId: String, index: Int): String = "rename-account/$walletId/$index"

    /** The accounts as a comma-separated list of indices, for example "1,3,4". */
    fun renameAccounts(walletId: String, indices: Collection<Int>): String =
        "rename-accounts/$walletId/${indices.joinToString(",")}"
}
