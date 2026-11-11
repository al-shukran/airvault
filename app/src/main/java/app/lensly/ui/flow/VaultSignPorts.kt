package app.lensly.ui.flow

import android.content.Context
import app.lensly.app.RegistryHolder
import app.lensly.app.ReviewHistory
import app.lensly.app.VaultHolder
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletSecret
import app.lensly.app.WalletStore
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewRecord
import app.lensly.app.sign.SignPipeline
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.reviewRecordOf
import app.lensly.app.visualIdentity
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The app's [SignFlowPorts]. Wallet reads go through the open session, and only [privateKey] reads a secret. The address check
 * and the review use the public xpub, so the mnemonic is not needed to show them.
 */
internal class VaultSignPorts(
    private val context: Context,
    private val holder: VaultHolder,
    /** Writes [ReviewRecord] [record] of wallet [walletId] through [session]. */
    private val writeRecord: (VaultSession, String, ReviewRecord) -> Unit = { session, walletId, record ->
        ReviewHistory(session).append(walletId, record)
    },
) : SignFlowPorts {

    override suspend fun wallets(): List<WalletView> = withContext(Dispatchers.IO) {
        readWallet { session ->
            WalletStore(session).list().mapIndexedNotNull { position, meta -> viewOf(session, meta, position) }
        }
    }

    override suspend fun prepare(walletId: String, request: EthSignRequest): Prepared = withContext(Dispatchers.IO) {
        val wallet = readWallet { session ->
            val listed = WalletStore(session).list()
            val position = listed.indexOfFirst { it.id == walletId }
            if (position < 0) null else viewOf(session, listed[position], position)
        }
        SignPipeline(RegistryHolder.get(context)).prepare(request, wallet)
    }

    override suspend fun privateKey(walletId: String, accountIndex: Int): ByteArray = withContext(Dispatchers.IO) {
        when (val secret = readWallet { WalletStore(it).secret(walletId) }) {
            is WalletSecret.Mnemonic -> {
                val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
                try {
                    HdWallet(seed).privateKey(accountIndex)
                } finally {
                    seed.fill(0)
                }
            }
            is WalletSecret.SingleKey -> {
                // The one account of a Single Key is the key itself. The caller zeroes the array it gets back.
                if (accountIndex != 0) {
                    secret.privateKey.fill(0)
                    throw IllegalArgumentException("A Single Key wallet has one account.")
                }
                secret.privateKey
            }
        }
    }

    /** Opens a second session only to prove the password, then closes it at once. The open session is left alone. */
    override suspend fun checkPassword(password: CharArray): PasswordCheck = try {
        when (val result = holder.vault.unlock(password)) {
            is UnlockResult.Unlocked -> {
                result.session.close()
                PasswordCheck.Accepted
            }
            UnlockResult.Rejected -> PasswordCheck.Rejected
        }
    } catch (e: VaultException) {
        PasswordCheck.Unavailable
    }

    override suspend fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> =
        withContext(Dispatchers.Default) {
            SignPipeline(RegistryHolder.get(context)).sign(ready, privateKey)
        }

    /**
     * Writes [entry] as a Review Record of its wallet, through the open session. NonCancellable, so a record that has started is
     * written even when the screen that asked for it is left.
     */
    override suspend fun record(entry: ReviewEntry): Unit = withContext(Dispatchers.IO + NonCancellable) {
        val session = holder.openSession() ?: throw ReviewNotSaved()
        try {
            val record = reviewRecordOf(entry, System.currentTimeMillis(), RegistryHolder.get(context))
            writeRecord(session, entry.walletId, record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A closed or damaged vault, unwritable storage, or a record that cannot be written.
            throw ReviewNotSaved()
        }
    }

    /**
     * The view of wallet [meta], which sits at [position] in the list the owner sees. Its name and Visual Identity, and the
     * Visual Identity of each account, come from the owner's profile records. A Single Key's address is read from its key,
     * which is zeroed again. Null when the wallet's records cannot be read, so a scan of every wallet skips it.
     */
    private fun viewOf(session: VaultSession, meta: WalletMeta, position: Int): WalletView? {
        val store = WalletStore(session)
        return try {
            val profile = store.walletProfile(meta.id)
            val profiles = store.accountProfiles(meta.id, meta.accountCount)
            val identity = visualIdentity(profile.colour, profile.icon, position)
            val accountNames = profiles.mapNotNull { (index, accountProfile) -> accountProfile.name?.let { index to it } }.toMap()
            val accountIdentity = { index: Int ->
                profiles[index].let { accountProfile -> visualIdentity(accountProfile?.colour, accountProfile?.icon, index) }
            }
            when (meta.kind) {
                WalletKind.MNEMONIC -> {
                    val xpub = meta.accountXpub
                    val fingerprint = meta.masterFingerprintHex?.toLongOrNull(16)?.toInt()
                    if (xpub == null || fingerprint == null) {
                        null
                    } else {
                        WalletView(
                            id = meta.id,
                            masterFingerprint = fingerprint,
                            accountCount = meta.accountCount,
                            autoNameBase = meta.autoNameBase,
                            accountNames = accountNames,
                            addressAt = { index -> AccountAddresses.addressAt(xpub, index) },
                            name = meta.name,
                            identity = identity,
                            accountIdentity = accountIdentity,
                        )
                    }
                }
                // The address is the only public fact a Single Key has, so the key is read once, for its address.
                WalletKind.SINGLE_KEY -> WalletView.singleKey(
                    id = meta.id,
                    address = store.addresses(meta.id, 1).single(),
                    autoNameBase = meta.autoNameBase,
                    // The one account is index 0. Its name is absent while the owner has not renamed it.
                    accountName = accountNames[0],
                    name = meta.name,
                    identity = identity,
                    accountIdentity = accountIdentity(0),
                )
            }
        } catch (e: IllegalStateException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Runs [read] on the open session. Throws [WalletUnavailable] when the vault is closed, when it is damaged (a damaged
     * vault throws IllegalStateException too), or when [read] finds nothing.
     */
    private inline fun <T : Any> readWallet(read: (VaultSession) -> T?): T {
        val session = holder.openSession() ?: throw WalletUnavailable()
        val value = try {
            read(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw WalletUnavailable()
        }
        return value ?: throw WalletUnavailable()
    }
}
