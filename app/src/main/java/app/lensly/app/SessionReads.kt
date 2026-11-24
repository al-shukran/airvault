package app.lensly.app

import app.lensly.core.vault.VaultSession
import app.lensly.ui.home.HomeReadiness

/** The wallets of an open vault, their profiles, and the number of accounts of every App each one holds: what Home and the Wallets list show. */
class WalletListing(val wallets: List<WalletMeta>, val profiles: Map<String, WalletProfile>, val accounts: Map<String, Int>)

/**
 * The reads that Home, the Wallets list and an App show, kept for one open session. [VaultHolder] makes one when a session opens and
 * wipes it on lock, so a read never outlives its session. Each wallet read is kept with the write count it was taken at, and it is used
 * only while that count still holds. A change to a wallet, an account or a Review Record moves the count, so the next visit reads again.
 * The readiness tiles are kept too, and every visit reads them again, so a visit shows the last tiles at once and then any change.
 */
class SessionReads(private val session: VaultSession) : SessionAttachment {
    @Volatile private var listing: Taken<WalletListing>? = null
    @Volatile private var recent: Taken<List<WalletReviewRecord>>? = null
    @Volatile private var readiness: HomeReadiness? = null
    /** The Ethereum views by wallet id. Guarded by this. */
    private val ethereum = HashMap<String, Taken<EthereumAccounts>>()

    /** Drops every kept read. [VaultHolder] calls it on lock, so no public view of the vault is held after it. */
    override fun wipe() {
        listing = null
        recent = null
        readiness = null
        synchronized(this) { ethereum.clear() }
    }

    /** The readiness tiles of the last read, or null when there has been none. */
    fun lastReadiness(): HomeReadiness? = readiness

    /** Keeps [value] as the readiness tiles that the next visit shows at once. */
    fun keepReadiness(value: HomeReadiness) {
        readiness = value
    }

    /** The kept wallets and profiles, or null when there are none or a write has passed them. Cheap, so it can run on the main thread. */
    fun cachedListing(): WalletListing? = listing.current()

    /** The wallets and their profiles, from the kept read when it still holds. Does file and crypto work: call it off the main thread. */
    fun listing(): WalletListing {
        val writes = session.writeCount
        cachedListing()?.let { return it }
        val store = WalletStore(session)
        val wallets = store.list()
        val profiles = wallets.associate { it.id to store.walletProfile(it.id) }
        val accounts = wallets.associate { it.id to store.accountCounts(it.id).values.sum() }
        return WalletListing(wallets, profiles, accounts).also { listing = Taken(writes, it) }
    }

    /** The kept Review Records of every wallet, or null when there are none or a write has passed them. */
    fun cachedRecent(): List<WalletReviewRecord>? = recent.current()

    /** The Review Records of every wallet, newest first, from the kept read when it still holds. Call it off the main thread. */
    fun recent(): List<WalletReviewRecord> {
        val writes = session.writeCount
        cachedRecent()?.let { return it }
        return ReviewHistory(session).listAll().also { recent = Taken(writes, it) }
    }

    /**
     * The kept Ethereum view of wallet [walletId], or null when there is none or a write has passed it. Cheap, so an App can read it on
     * the main thread and show its list at once.
     */
    fun cachedEthereumAccounts(walletId: String): EthereumAccounts? = synchronized(this) { ethereum[walletId].current() }

    /**
     * The Ethereum view of wallet [walletId], from the kept view when it still holds. Does file and crypto work: call it off the main
     * thread. Null if the wallet does not exist.
     */
    fun ethereumAccounts(walletId: String): EthereumAccounts? {
        val writes = session.writeCount
        cachedEthereumAccounts(walletId)?.let { return it }
        val view = WalletStore(session).ethereumAccounts(walletId) ?: return null
        synchronized(this) { ethereum[walletId] = Taken(writes, view) }
        return view
    }

    private fun <T> Taken<T>?.current(): T? = this?.takeIf { it.writes == session.writeCount }?.value
}

/** A read and the write count it was taken at. */
private class Taken<T>(val writes: Long, val value: T)

/** The reads kept for the open session, or null while the vault is locked. */
fun VaultHolder.sessionReads(): SessionReads? = sessionAttachment() as? SessionReads
