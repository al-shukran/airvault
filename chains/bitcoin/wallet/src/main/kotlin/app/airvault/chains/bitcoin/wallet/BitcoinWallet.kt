package app.airvault.chains.bitcoin.wallet

import app.airvault.chains.bitcoin.BitcoinAddressCodec
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.AccountId
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressKind
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.wallet.WalletAccount
import app.airvault.core.wallet.WalletApp
import app.lensly.core.ur.PathStep

/**
 * The Lumio side of the Bitcoin chain app: its manifest, its native segwit address codec, and the accounts a pairing gives it. Its
 * providers, assets and transaction builder arrive with the changes that own their types (spec 3.3).
 *
 * An account here is one native segwit key, the first receive key of a BIP84 account: m/84'/coin'/N'/0/0. Its address is the P2WPKH of
 * that key. The watch-only extended-key accounts Lumio pairs today carry a chain code, which an [AccountExport] does not hold yet, so
 * they do not come through [accountFrom].
 */
object BitcoinWallet : WalletApp {
    override val manifest: ChainManifest = bitcoinManifest

    override val addresses: AddressCodec = BitcoinAddressCodec

    /** The BIP43 purpose of native segwit. The codec encodes P2WPKH only, so no other purpose is an account here. */
    private const val PURPOSE = 84

    /** A compressed secp256k1 public key. */
    private const val COMPRESSED_KEY_BYTES = 33

    /**
     * The account of an exported Bitcoin key on the network [AccountExport.chain]. The key must be a compressed secp256k1 key at
     * m/84'/coin'/N'/0/0, where coin is the network's SLIP-44 coin type. Anything else throws [IllegalArgumentException].
     */
    override fun accountFrom(export: AccountExport): List<WalletAccount> {
        val coin = coinOf(export.chain)
        require(export.kind == AddressKind.DEFAULT) { "Bitcoin accounts here are native segwit, the default kind" }
        require(export.publicKey.curve == Curve.SECP256K1) { "a Bitcoin key is a ${Curve.SECP256K1} key" }
        require(export.publicKey.bytes.size == COMPRESSED_KEY_BYTES) { "a Bitcoin key is $COMPRESSED_KEY_BYTES bytes, compressed" }
        require(isAccountKeyPath(export.signer.path, coin)) { "${export.signer.path} is not a BIP84 account key, m/84'/$coin'/N'/0/0" }
        val address = addresses.fromPublicKey(export.chain, export.publicKey.bytes, export.kind)
        return listOf(WalletAccount(AccountId.of(export.chain, address), export.signer, export.publicKey, export.kind))
    }

    /** The path of Bitcoin account [index] on [chain]: m/84'/coin'/[index]'/0/0, the account's first receive key. */
    fun pathOf(chain: ChainId, index: Int): List<PathStep> {
        val coin = coinOf(chain)
        return listOf(PathStep(PURPOSE, true), PathStep(coin, true), PathStep(index, true), PathStep(0, false), PathStep(0, false))
    }

    private fun coinOf(chain: ChainId): Int {
        val network = manifest.networks.firstOrNull { it.chainId == chain }
        require(network != null) { "$chain is not a Bitcoin network" }
        return network.slip44
    }

    private fun isAccountKeyPath(steps: List<PathStep>, coin: Int): Boolean {
        return steps.size == 5 &&
            steps[0] == PathStep(PURPOSE, true) &&
            steps[1] == PathStep(coin, true) &&
            steps[2].hardened &&
            steps[3] == PathStep(0, false) &&
            steps[4] == PathStep(0, false)
    }
}
