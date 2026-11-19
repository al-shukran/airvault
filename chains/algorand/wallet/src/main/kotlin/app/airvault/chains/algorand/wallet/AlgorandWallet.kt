package app.airvault.chains.algorand.wallet

import app.airvault.chains.algorand.ALGORAND_COIN_TYPE
import app.airvault.chains.algorand.AlgorandAddressCodec
import app.airvault.chains.algorand.algorandManifest
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
 * The Lumio side of the Algorand chain app: its manifest, its address codec, and the accounts a pairing gives it. Its providers,
 * assets and transaction builder arrive with the changes that own their types (spec 3.3).
 */
object AlgorandWallet : WalletApp {
    override val manifest: ChainManifest = algorandManifest

    override val addresses: AddressCodec = AlgorandAddressCodec

    private val networks: Set<ChainId> = manifest.networks.map { it.chainId }.toSet()

    /**
     * The account of an exported Algorand key on the network [AccountExport.chain]. An Algorand key is the same on both networks, so
     * the id names the network the account is shown on. The key must be an Ed25519 public key at m/44'/283'/N'/0/0 (the key scope
     * of the manifest). Anything else throws [IllegalArgumentException].
     */
    override fun accountFrom(export: AccountExport): List<WalletAccount> {
        require(export.chain in networks) { "${export.chain} is not an Algorand network" }
        require(export.kind == AddressKind.DEFAULT) { "Algorand accounts have one address kind" }
        require(export.publicKey.curve == Curve.ED25519_BIP32) { "an Algorand key is an ${Curve.ED25519_BIP32} key" }
        require(isAccountPath(export.signer.path)) { "${export.signer.path} is not an Algorand account key, m/44'/283'/N'/0/0" }
        val address = addresses.fromPublicKey(export.chain, export.publicKey.bytes, export.kind)
        return listOf(WalletAccount(AccountId.of(export.chain, address), export.signer, export.publicKey, export.kind))
    }

    /** The path of Algorand account [index]: m/44'/283'/[index]'/0/0, the account level hardened and the external chain at 0. */
    fun pathOf(index: Int): List<PathStep> =
        listOf(PathStep(PURPOSE, true), PathStep(ALGORAND_COIN_TYPE, true), PathStep(index, true), PathStep(0, false), PathStep(0, false))

    private const val PURPOSE = 44

    private fun isAccountPath(steps: List<PathStep>): Boolean {
        return steps.size == 5 &&
            steps[0] == PathStep(PURPOSE, true) &&
            steps[1] == PathStep(ALGORAND_COIN_TYPE, true) &&
            steps[2].hardened &&
            steps[3] == PathStep(0, false) &&
            steps[4] == PathStep(0, false)
    }
}
