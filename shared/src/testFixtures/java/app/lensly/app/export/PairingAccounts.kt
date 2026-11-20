package app.lensly.app.export

import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.bitcoin.bitcoinTestnet4
import app.airvault.core.model.AddressKind
import app.airvault.core.model.ChainId
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.ur.PathStep

/** The path m/purpose'/coin'/[account]' followed by [suffix], whose levels after the account are not hardened. */
fun accountSteps(purpose: Int, coin: Int, account: Int, vararg suffix: Int): List<PathStep> =
    listOf(PathStep(purpose, true), PathStep(coin, true), PathStep(account, true)) + suffix.map { PathStep(it, false) }

/** The export of one key on [chain]: the key at [path] of the wallet with [fingerprint], with its public key on [curve]. */
fun keyExport(chain: ChainId, fingerprint: Int, path: List<PathStep>, curve: Curve, publicKey: ByteArray): AccountExport =
    AccountExport(chain, SignerRef(fingerprint, path), PublicKey(curve, publicKey), null, AddressKind.DEFAULT)

/** The export of a watch-only Bitcoin account: its network's chain, its account path, and its public key and chain code. */
fun watchOnlyExportOf(account: BtcWatchOnlyAccount, fingerprint: Int): AccountExport = AccountExport(
    chain = if (account.network == BtcNetwork.MAINNET) bitcoinMainnet else bitcoinTestnet4,
    signer = SignerRef(fingerprint, account.path.path.map { child -> PathStep((child and INDEX_BITS).toInt(), child and HARDENED_BIT != 0L) }),
    publicKey = PublicKey(Curve.SECP256K1, account.xpub.publickeybytes.toByteArray()),
    chainCode = account.xpub.chaincode.toByteArray(),
    kind = AddressKind.DEFAULT,
)

private const val HARDENED_BIT = 0x8000_0000L
private const val INDEX_BITS = 0x7FFF_FFFFL
