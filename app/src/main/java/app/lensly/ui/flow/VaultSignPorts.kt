package app.lensly.ui.flow

import android.content.Context
import app.lensly.app.RegistryHolder
import app.lensly.app.VaultHolder
import app.lensly.app.WalletSecret
import app.lensly.app.WalletStore
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.SignPipeline
import app.lensly.app.sign.WalletView
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's [SignFlowPorts] for one wallet. Wallet reads go through the open session, and only [privateKey] reads the
 * secret. The address check and the review use the public xpub, so the mnemonic is not needed to show them.
 */
internal class VaultSignPorts(
    private val context: Context,
    private val holder: VaultHolder,
    private val walletId: String,
) : SignFlowPorts {

    override suspend fun prepare(request: EthSignRequest): Prepared = withContext(Dispatchers.IO) {
        val meta = readWallet { WalletStore(it).meta(walletId) }
        val xpub = requireNotNull(meta.accountXpub) { "A Mnemonic wallet has an account public key." }
        val wallet = WalletView(
            masterFingerprint = requireNotNull(meta.masterFingerprintHex).toLong(16).toInt(),
            accountCount = meta.accountCount,
            addressAt = { index -> AccountAddresses.addressAt(xpub, index) },
        )
        SignPipeline(RegistryHolder.get(context)).prepare(request, wallet)
    }

    override suspend fun privateKey(accountIndex: Int): ByteArray = withContext(Dispatchers.IO) {
        when (val secret = readWallet { WalletStore(it).secret(walletId) }) {
            is WalletSecret.Mnemonic -> {
                val seed = Mnemonic.toSeed(secret.words, secret.passphrase.orEmpty())
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
     * Runs [read] on the open session. Throws [WalletUnavailable] when the vault is closed, when it is damaged (a damaged
     * vault throws IllegalStateException too), or when the record is missing.
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
