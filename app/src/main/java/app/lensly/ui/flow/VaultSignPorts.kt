package app.lensly.ui.flow

import android.content.Context
import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.bitcoin.signer.BitcoinSigner
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.BtcRefusal
import app.airvault.chains.bitcoin.signer.BtcRefusedException
import app.airvault.chains.bitcoin.signer.NOT_A_PSBT
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.chains.evm.signer.EvmRequestBody
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.toReview
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.Approval
import app.airvault.core.model.AppId
import app.airvault.core.model.ChainId
import app.airvault.core.model.Curve
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.SignerRef
import app.airvault.core.signer.ScopedKeyAccess
import app.airvault.core.signer.SignerApp
import app.airvault.core.signer.UNKNOWN_REQUEST
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.AddressBookStore
import app.lensly.app.AddressNotes
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.RegistryHolder
import app.lensly.app.ReviewHistory
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.SettingsStore
import app.lensly.app.SigningAccount
import app.lensly.app.SolanaTokensHolder
import app.lensly.app.VaultHolder
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletStore
import app.lensly.app.addressNotesOf
import app.lensly.app.autoAccountName
import app.lensly.app.export.partsOf
import app.lensly.app.internalAccountsOf
import app.lensly.app.requestParser
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.AsaCatalogHolder
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewRecord
import app.lensly.app.sign.SignPipeline
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.algorandCounterparties
import app.lensly.app.sign.appRequestFactsOf
import app.lensly.app.sign.appReviewRecordOf
import app.lensly.app.sign.bitcoinRecordFactsOf
import app.lensly.app.sign.bitcoinReviewRecordOf
import app.lensly.app.sign.counterpartiesOf
import app.lensly.app.sign.evmCounterparties
import app.lensly.app.visualIdentity
import app.lensly.chains.installedSignerOf
import app.lensly.ui.sign.bitcoinReviewOf
import app.airvault.chains.algorand.signer.AlgorandRequestCheck
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.WireException
import app.lensly.core.registry.UnknownCallPolicy
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaTokenList
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.airvault.chains.algorand.signer.toReview
import com.sparrowwallet.hummingbird.UR

/** The refusal of a Bitcoin request that could not be reviewed for a reason other than its rules. Nothing is signed for it. */
private const val BITCOIN_UNREADABLE = "This Bitcoin request could not be reviewed. Nothing was signed."

/** The refusal of a Solana request to a Single Key wallet, which has no Solana accounts. Nothing is signed for it. */
private const val SOLANA_NO_ACCOUNTS = "This wallet has no Solana accounts."

/** The refusal of a Solana request that could not be reviewed for a reason other than its rules. Nothing is signed for it. */
private const val SOLANA_UNREADABLE = "This Solana request could not be reviewed. Nothing was signed."

/** The refusal of a Solana request with no requestId, which its response must echo. Nothing is signed for it. */
private const val SOLANA_NO_REQUEST_ID = "Request has no ID"

/** The refusal of a Solana key that is not the public key the wallet stores for the reviewed account. Nothing is signed for it. */
private const val SOLANA_KEY_MISMATCH = "Key does not match account"

/** The refusal of an Algorand request to a Single Key wallet, which has no Algorand accounts. Nothing is signed for it. */
private const val ALGORAND_NO_ACCOUNTS = "This wallet has no Algorand accounts."

/** The refusal of an Ethereum request that could not be reviewed for a reason other than its rules. Nothing is signed for it. */
private const val EVM_UNREADABLE = "This Ethereum request could not be reviewed. Nothing was signed."

/** The refusal of an Algorand request that could not be reviewed for a reason other than its rules. Nothing is signed for it. */
private const val ALGORAND_UNREADABLE = "This Algorand request could not be reviewed. Nothing was signed."

/**
 * Runs one check of a request on its way to a review. A RuntimeException the check throws becomes the refusal [refused] makes of
 * [unreadable], so a check that fails unexpectedly refuses the request and never escapes the sign flow. A cancellation and a
 * [WalletUnavailable] propagate, as the flow handles them. Every chain's prepare runs its check through here, so a new chain cannot
 * leave its check unguarded.
 */
internal inline fun <T> guardedCheck(unreadable: String, refused: (String) -> T, check: () -> T): T = try {
    check()
} catch (e: CancellationException) {
    throw e
} catch (e: RuntimeException) {
    refused(unreadable)
}

/**
 * The app's [SignFlowPorts]. Wallet reads go through the open session, and only [appSign] reads a secret. The address check
 * and the review use the public xpub, so the mnemonic is not needed to show them. Everything scanned is parsed by [parser],
 * which is the Parser Sandbox.
 */
internal class VaultSignPorts(
    private val context: Context,
    private val holder: VaultHolder,
    private val parser: RequestParser = context.requestParser(),
    /** Checks a Solana request against the wallet's keys and token list. A test replaces it with one that throws. */
    private val checkSolana: (SolSignRequest, List<ByteArray>, SolanaTokenList, UnknownCallPolicy) -> SolanaRequestCheck.Result =
        SolanaRequestCheck::check,
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

    override suspend fun scan(parts: List<String>): ScanReply = withContext(Dispatchers.IO) { parser.scan(parts) }

    override suspend fun isInstalled(walletId: String, app: WalletApp): Boolean = withContext(Dispatchers.IO) {
        readWallet { session -> app in WalletStore(session).installedApps(walletId) }
    }

    /** The account that signs a request, as its review names it: its name, its wallet's name and its address. */
    private fun signerOf(wallet: WalletView, index: Int, address: String): SigningAccount = SigningAccount(
        name = wallet.accountNames[index] ?: autoAccountName(wallet.autoNameBase, index),
        walletName = wallet.name,
        address = address,
    )

    /**
     * The notes of [addresses] that a review of wallet [walletId] shows: what the Address Book and the vault's accounts say of each, on every
     * chain. A damaged Address Book shows as none, so the review still opens. The read signs nothing.
     */
    private fun addressNotesFor(walletId: String, addresses: Collection<String>, chain: ChainId): Map<String, AddressNotes> {
        if (addresses.isEmpty()) return emptyMap()
        return try {
            readWallet { session ->
                val book = try {
                    AddressBookStore(session).load()
                } catch (e: VaultException) {
                    emptyList()
                }
                addressNotesOf(addresses, chain, internalAccountsOf(WalletStore(session), walletId), book)
            }
        } catch (e: WalletUnavailable) {
            emptyMap()
        }
    }

    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    /**
     * Reviews the request [request] of an installed chain app against wallet [walletId]. Every installed chain app has a review on the one sign
     * flow, and any other app is refused.
     */
    override suspend fun appPrepare(walletId: String, request: AppRequest): AppPrepared = when (request.header.app) {
        algorandManifest.id -> algorandPrepare(walletId, request)
        solanaManifest.id -> solanaPrepare(walletId, request)
        bitcoinManifest.id -> bitcoinPrepare(walletId, request)
        evmManifest.id -> evmPrepare(walletId, request)
        else -> AppPrepared.Refused(UNKNOWN_REQUEST)
    }

    /**
     * Reviews the Algorand [appRequest] against wallet [walletId]. The request must name the wallet's master fingerprint, and the review uses
     * the Algorand public keys the wallet stores, so the seed is not read here. A request the rules refuse is [AppPrepared.Refused].
     */
    private suspend fun algorandPrepare(walletId: String, appRequest: AppRequest): AppPrepared = withContext(Dispatchers.IO) {
        val request = when (val decoded = AlgoSignRequest.decode(appRequest.body)) {
            is AlgoSignRequest.Result.Ok -> decoded.request
            is AlgoSignRequest.Result.Rejected -> return@withContext AppPrepared.Refused(decoded.reason)
        }
        val wallet = readWallet { session ->
            val listed = WalletStore(session).list()
            val position = listed.indexOfFirst { it.id == walletId }
            if (position < 0) null else viewOf(session, listed[position], position)
        }
        val masterFingerprint = wallet.masterFingerprint ?: return@withContext AppPrepared.Refused(ALGORAND_NO_ACCOUNTS)
        val sourceFingerprint = request.sourceFingerprint
            ?: return@withContext AppPrepared.Refused("The request does not say which wallet it is for")
        if (sourceFingerprint != masterFingerprint) return@withContext AppPrepared.Refused("This request is for a different wallet")
        val keys = readWallet { WalletStore(it).algorandKeys(walletId) }
        val checked = guardedCheck(ALGORAND_UNREADABLE, { AlgorandRequestCheck.Result.Refused(it) }) {
            AlgorandRequestCheck.check(request, keys, AsaCatalogHolder.get(context))
        }
        when (checked) {
            is AlgorandRequestCheck.Result.Ok -> {
                val review = checked.review
                val counterparties = algorandCounterparties(review)
                AppPrepared.Ready(
                    walletId = walletId,
                    request = appRequest,
                    review = review.toReview(),
                    accountIndex = review.accountIndex,
                    signer = signerOf(wallet, review.accountIndex, review.signerAddress),
                    addressChain = ALGORAND_FAMILY,
                    counterparties = counterparties,
                    addressNotes = addressNotesFor(walletId, counterparties, ALGORAND_FAMILY),
                )
            }
            is AlgorandRequestCheck.Result.Refused -> AppPrepared.Refused(checked.reason)
        }
    }

    /**
     * Reviews the Solana [appRequest] against wallet [walletId]. The request must name a requestId, which its response echoes, and the wallet's
     * master fingerprint. The review uses the Solana public keys the wallet stores, so the seed is not read here. A request the rules refuse is
     * [AppPrepared.Refused].
     */
    private suspend fun solanaPrepare(walletId: String, appRequest: AppRequest): AppPrepared = withContext(Dispatchers.IO) {
        val request = when (val decoded = SolSignRequest.decode(appRequest.body)) {
            is SolSignRequest.Result.Ok -> decoded.request
            is SolSignRequest.Result.Rejected -> return@withContext AppPrepared.Refused(decoded.reason)
        }
        if (request.requestId == null) return@withContext AppPrepared.Refused(SOLANA_NO_REQUEST_ID)
        val wallet = readWallet { session ->
            val listed = WalletStore(session).list()
            val position = listed.indexOfFirst { it.id == walletId }
            if (position < 0) null else viewOf(session, listed[position], position)
        }
        val masterFingerprint = wallet.masterFingerprint ?: return@withContext AppPrepared.Refused(SOLANA_NO_ACCOUNTS)
        val sourceFingerprint = request.sourceFingerprint
            ?: return@withContext AppPrepared.Refused("The request does not say which wallet it is for")
        if (sourceFingerprint != masterFingerprint) return@withContext AppPrepared.Refused("This request is for a different wallet")
        val keys = readWallet { WalletStore(it).solanaKeys(walletId) }
        val unknownCalls = readWallet { SettingsStore(it).load().unknownCallPolicy }
        val checked = guardedCheck(SOLANA_UNREADABLE, { SolanaRequestCheck.Result.Refused(it) }) {
            checkSolana(request, keys, SolanaTokensHolder.get(context), unknownCalls)
        }
        when (checked) {
            is SolanaRequestCheck.Result.Ok -> {
                val review = checked.review
                val counterparties = counterpartiesOf(review.toReview())
                AppPrepared.Ready(
                    walletId = walletId,
                    request = appRequest,
                    review = review.toReview(),
                    accountIndex = review.accountIndex,
                    signer = signerOf(wallet, review.accountIndex, review.signerAddress),
                    addressChain = SOLANA_FAMILY,
                    counterparties = counterparties,
                    addressNotes = addressNotesFor(walletId, counterparties, SOLANA_FAMILY),
                )
            }
            is SolanaRequestCheck.Result.Refused -> AppPrepared.Refused(checked.reason)
        }
    }

    /**
     * Reviews the Bitcoin [appRequest] against wallet [walletId], under the owner's Bitcoin policies. The request keeps the signer references of
     * wallet [walletId] alone, so its signature names the one account it was reviewed under. A request whose claimed inputs belong to two vault
     * wallets is refused as [BtcRefusal.SeveralAccounts], since one signature cannot cover both. A request the rules refuse is [AppPrepared.Refused].
     */
    private suspend fun bitcoinPrepare(walletId: String, appRequest: AppRequest): AppPrepared = withContext(Dispatchers.IO) {
        val parsed = try {
            BtcPsbt.parse(BtcPsbt.requestPsbt(appRequest.body))
        } catch (e: RuntimeException) {
            return@withContext AppPrepared.Refused(NOT_A_PSBT)
        }
        val wallet = readWallet { session ->
            val listed = WalletStore(session).list()
            val position = listed.indexOfFirst { it.id == walletId }
            if (position < 0) null else viewOf(session, listed[position], position)
        }
        val masterFingerprint = wallet.masterFingerprint ?: return@withContext AppPrepared.Refused(BtcRefusal.NoStoredAccount.reason)
        val vaultFingerprints = readWallet { session ->
            WalletStore(session).list().mapNotNull { meta -> meta.masterFingerprintHex?.toLongOrNull(16)?.toInt() }.toSet()
        }
        val claimed = appRequest.header.signers.mapNotNull { it.fingerprint }.filter { it in vaultFingerprints }.distinct()
        if (claimed.size > 1) return@withContext AppPrepared.Refused(BtcRefusal.SeveralAccounts.reason)
        val request = appRequest.copy(
            header = appRequest.header.copy(signers = appRequest.header.signers.filter { it.fingerprint == masterFingerprint }),
        )
        val session = holder.openSession() ?: throw WalletUnavailable()
        try {
            val settings = SettingsStore(session).load()
            val book = try {
                AddressBookStore(session).load()
            } catch (e: VaultException) {
                emptyList()
            }
            val reviewed = WalletStore(session).bitcoinReview(
                walletId,
                parsed,
                settings.bitcoinInputPolicy,
                settings.bitcoinSigningSource(),
                settings.bitcoinFeeThresholds,
                book,
            )
            val review = bitcoinReviewOf(reviewed.review, reviewed.account.network, reviewed.account.scriptType, reviewed.signer.address)
            AppPrepared.Ready(
                walletId = walletId,
                request = request,
                review = review,
                accountIndex = reviewed.account.index,
                signer = reviewed.signer,
                addressChain = BITCOIN_FAMILY,
                counterparties = listOf(reviewed.signer.address) + reviewed.review.outputs.mapNotNull { it.address },
                addressNotes = reviewed.addressNotes,
                bitcoin = bitcoinRecordFactsOf(reviewed, review),
            )
        } catch (e: BtcRefusedException) {
            AppPrepared.Refused(e.refusal.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw WalletUnavailable()
        } catch (e: RuntimeException) {
            AppPrepared.Refused(BITCOIN_UNREADABLE)
        }
    }

    /**
     * Reviews the Ethereum [appRequest] against wallet [walletId], by the rules [SignPipeline] checks: the wallet's master fingerprint and account
     * path, or its address for a Single Key, and the registry. The request is read again from its body, as the sign flow reads it. A request the
     * rules refuse is [AppPrepared.Refused], with the undecoded call the refusal shows, if any.
     */
    private suspend fun evmPrepare(walletId: String, appRequest: AppRequest): AppPrepared = withContext(Dispatchers.IO) {
        val parsed = try {
            EvmRequestBody.decode(appRequest.body)
        } catch (e: WireException) {
            return@withContext AppPrepared.Refused(CANNOT_BE_READ)
        }
        val wallet = readWallet { session ->
            val listed = WalletStore(session).list()
            val position = listed.indexOfFirst { it.id == walletId }
            if (position < 0) null else viewOf(session, listed[position], position)
        }
        val settings = readWallet { SettingsStore(it).load() }
        val prepared = guardedCheck(EVM_UNREADABLE, { Prepared.Refused(it) }) {
            SignPipeline(RegistryHolder.get(context), parser, settings.proxyPolicy, settings.unknownCallPolicy).prepare(parsed, wallet)
        }
        when (prepared) {
            is Prepared.Ready -> {
                val counterparties = evmCounterparties(prepared.review)
                // The review does not read the path of a Single Key request, so the signature is made at the path of its one key.
                val request = if (wallet.masterFingerprint == null) {
                    appRequest.copy(header = appRequest.header.copy(signers = listOf(SignerRef(null, EvmSigner.singleKeyPath.steps))))
                } else {
                    appRequest
                }
                AppPrepared.Ready(
                    walletId = walletId,
                    request = request,
                    review = prepared.review,
                    accountIndex = prepared.accountIndex,
                    signer = prepared.signer,
                    addressChain = EVM_FAMILY,
                    counterparties = counterparties,
                    addressNotes = addressNotesFor(walletId, counterparties, EVM_FAMILY),
                )
            }
            is Prepared.Refused -> AppPrepared.Refused(prepared.reason, prepared.rows)
        }
    }

    /**
     * Signs the approved request through its app's `SignerApp.sign`, with the wallet's key access scoped to the app's key scopes, so a path outside
     * them throws [KeyScopeViolation] before any key is derived. The seed is zeroed inside [WalletStore.withKeys]. A Bitcoin refusal reads as the
     * refusal of any other app: its reason is the message.
     */
    override suspend fun appSign(ready: AppPrepared.Ready, approval: Approval): List<String> = withContext(Dispatchers.Default) {
        val session = holder.openSession() ?: throw WalletUnavailable()
        try {
            val signer = signerOfApp(session, ready.request.header.app) ?: throw IllegalArgumentException(UNKNOWN_REQUEST)
            val response = WalletStore(session).withKeys(ready.walletId) { keys ->
                signer.sign(ready.request, approval, reviewedKeys(session, ready, ScopedKeyAccess(signer.manifest.keys, keys)))
            }
            partsOf(framesOf(UR(response.urType, response.cbor), DEFAULT_MAX_FRAGMENT))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw WalletUnavailable()
        } catch (e: BtcRefusedException) {
            throw IllegalArgumentException(e.refusal.reason)
        }
    }

    /**
     * The installed signer of app [app], which names itself in the header of each request. It is found by app and not by chain, so an Ethereum
     * request on a chain outside the manifest, which its review shows with a warning, is signed as well. The Bitcoin signer is built with the
     * owner's Bitcoin policies, so a signature is made under the policies its review was made under. The installed instance holds the core
     * defaults, and it is never used to sign a Bitcoin request.
     */
    private fun signerOfApp(session: VaultSession, app: AppId): SignerApp? {
        val installed = installedSignerOf(app) ?: return null
        if (installed !is BitcoinSigner) return installed
        val settings = SettingsStore(session).load()
        return BitcoinSigner(settings.bitcoinInputPolicy, settings.bitcoinSigningSource(), settings.bitcoinFeeThresholds)
    }

    /**
     * The key access that signs [ready], over [keys]. A Solana key must be the public key the wallet stores for the reviewed account, or nothing
     * is signed. The approval holds no signer bytes, so the Solana signer cannot make that check, and the app makes it here, before any signature.
     */
    private fun reviewedKeys(session: VaultSession, ready: AppPrepared.Ready, keys: KeyAccess): KeyAccess {
        if (ready.request.header.app != solanaManifest.id) return keys
        val stored = ready.accountIndex?.let { WalletStore(session).solanaKeys(ready.walletId).getOrNull(it) }
            ?: throw IllegalArgumentException(SolanaRequestCheck.UNKNOWN_ACCOUNT)
        return StoredKeyAccess(keys, stored)
    }

    /**
     * Writes the [entry] of a chain app as a Review Record of its wallet, through the open session. A Bitcoin request keeps its own record shape,
     * and every other app keeps the generic one. NonCancellable, so a record that has started is written even when the screen that asked for it
     * is left.
     */
    override suspend fun recordApp(entry: AppReviewEntry): Unit = withContext(Dispatchers.IO + NonCancellable) {
        val session = holder.openSession() ?: throw ReviewNotSaved()
        try {
            val now = System.currentTimeMillis()
            val record = if (entry.request.header.app == bitcoinManifest.id) {
                bitcoinReviewRecordOf(entry, now)
            } else {
                val (origin, dataHash) = appRequestFactsOf(entry.request)
                val displayName = installedSignerOf(entry.request.header.app)?.manifest?.displayName.orEmpty()
                appReviewRecordOf(entry, now, origin, dataHash, displayName)
            }
            writeRecord(session, entry.walletId, record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
                    val keys = meta.accountKeys
                    val fingerprint = meta.masterFingerprintHex?.toLongOrNull(16)?.toInt()
                    if (keys.size != meta.accountCount || fingerprint == null) {
                        null
                    } else {
                        WalletView(
                            id = meta.id,
                            masterFingerprint = fingerprint,
                            accountCount = meta.accountCount,
                            autoNameBase = meta.autoNameBase,
                            accountNames = accountNames,
                            addressAt = { account, index -> AccountAddresses.addressAt(keys[account], index) },
                            name = meta.name,
                            identity = identity,
                            accountIdentity = accountIdentity,
                            storedAccountPaths = store.storedAccountPaths(meta.id),
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

/**
 * The key access a Solana request signs through. Every call is the one [keys] answers, and a signature is made only with the key the wallet
 * stores for the reviewed account: a key that is not [stored] signs nothing.
 */
internal class StoredKeyAccess(private val keys: KeyAccess, private val stored: ByteArray) : KeyAccess by keys {
    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray {
        if (!keys.publicKey(curve, path).bytes.contentEquals(stored)) throw IllegalArgumentException(SOLANA_KEY_MISMATCH)
        return keys.signEd25519(curve, path, message)
    }
}
