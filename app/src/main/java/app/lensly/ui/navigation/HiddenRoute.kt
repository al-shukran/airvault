package app.lensly.ui.navigation

import app.lensly.ui.camera.EntryGesture

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
    // Every wallet as a card, reached from the See all link on Home or from the dock.
    const val WALLETS = "wallets"
    // Pairing wizard, from the dock. It starts at the wallet step, or at the app step when there is only one wallet.
    const val PAIR = "pair"
    const val DETAIL = "detail/{walletId}"
    const val BULK = "bulk/{walletId}"
    const val EXPORT = "export/{walletId}"
    // Export of one or more accounts: each account's code and its plain-text fields. Reached from an account's options or from
    // the selection of the wallet detail.
    const val EXPORT_ACCOUNTS = "export-accounts/{walletId}/{indices}"
    const val SCAN = "scan/{walletId}"
    const val SCAN_ANY = "scan"
    // Saved-state key on the hidden graph, not a route: Home shows the message once after a pairing, then forgets it.
    const val PAIRED_NOTICE = "paired-notice"

    // Show Secret: the App Password again, a warning, then the secret. Reached from the wallet detail.
    const val SHOW_SECRET = "secret/{walletId}"
    // Delete Wallet: the App Password again, a warning with its confirmation, then the shred. Reached from the wallet detail.
    const val DELETE_WALLET = "delete/{walletId}"
    // History: the Review Records of one wallet, newest first. Reached from the wallet detail's options.
    const val HISTORY = "history/{walletId}"
    // Profiles: a wallet's name, colour, icon, label and groups, one account's, and the style of a batch of accounts.
    const val EDIT_WALLET = "edit-wallet/{walletId}"
    const val EDIT_ACCOUNT = "edit-account/{walletId}/{index}"
    const val STYLE_ACCOUNTS = "style-accounts/{walletId}/{indices}"
    // Renames: a batch of accounts' Display Names, on the shared name screen.
    const val RENAME_ACCOUNTS = "rename-accounts/{walletId}/{indices}"

    // Catalog: installs a signed registry package from removable storage.
    const val CATALOG = "catalog"
    // Networks and tokens: the catalog's lists, read only. Reached from Settings.
    const val NETWORKS = "settings/networks"
    const val TOKENS = "settings/tokens"

    // Settings: the Bulk Cap, the catalog summary and the About notices. Reached from the wallet list.
    const val SETTINGS = "settings"
    const val CHANGE_PASSWORD = "settings/password"
    // Duress Password: set it (the App Password, then the new password twice) or remove it (the App Password, then a warning).
    const val DURESS = "settings/duress"
    const val DURESS_REMOVE = "settings/duress/remove"
    // Failure Delay cap: a choice, a warning below 10 s, then the App Password. Reached from Settings.
    const val FAILURE_CAP = "settings/failure-cap"
    // Wipe: the App Password again, a warning with its confirmation, then every file. Reached from Settings.
    const val WIPE = "settings/wipe"
    // Hidden entry gesture: the four gestures to choose from, then the change to one of them. Reached from Settings.
    const val ENTRY_GESTURE = "settings/gesture"
    const val ENTRY_GESTURE_CHANGE = "settings/gesture/change/{gestureId}"
    const val GESTURE_ID_ARG = "gestureId"

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

    /** The export of the accounts in [indices], as the same comma-separated list that the batch routes use. */
    fun exportAccounts(walletId: String, indices: Collection<Int>): String =
        "export-accounts/$walletId/${indices.joinToString(",")}"

    fun scan(walletId: String): String = "scan/$walletId"

    fun showSecret(walletId: String): String = "secret/$walletId"

    fun deleteWallet(walletId: String): String = "delete/$walletId"

    fun history(walletId: String): String = "history/$walletId"

    fun editWallet(walletId: String): String = "edit-wallet/$walletId"

    fun editAccount(walletId: String, index: Int): String = "edit-account/$walletId/$index"

    /** The style route of the accounts in [indices], as the same comma-separated list that the batch rename uses. */
    fun styleAccounts(walletId: String, indices: Collection<Int>): String =
        "style-accounts/$walletId/${indices.joinToString(",")}"

    /** The accounts as a comma-separated list of indices, for example "1,3,4". */
    fun renameAccounts(walletId: String, indices: Collection<Int>): String =
        "rename-accounts/$walletId/${indices.joinToString(",")}"

    fun entryGestureChange(gesture: EntryGesture): String = "settings/gesture/change/${gesture.storedId}"
}
