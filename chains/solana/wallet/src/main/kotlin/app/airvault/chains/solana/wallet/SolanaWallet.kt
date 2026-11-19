package app.airvault.chains.solana.wallet

import app.airvault.chains.solana.SOLANA_COIN_TYPE
import app.airvault.chains.solana.SolanaAddressCodec
import app.airvault.chains.solana.solanaManifest
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
 * The Lumio side of the Solana chain app: its manifest, its address codec, and the accounts a pairing gives it. Its providers,
 * assets and transaction builder arrive with the changes that own their types (spec 3.3).
 */
object SolanaWallet : WalletApp {
    override val manifest: ChainManifest = solanaManifest

    override val addresses: AddressCodec = SolanaAddressCodec

    private val networks: Set<ChainId> = manifest.networks.map { it.chainId }.toSet()

    /**
     * The account of an exported Solana key on the network [AccountExport.chain]. A Solana key is the same on every cluster, so the id
     * names the cluster the account is shown on. The key must be an Ed25519 (SLIP-0010) public key at m/44'/501'/N' (the key scope of
     * the manifest). Anything else throws [IllegalArgumentException].
     */
    override fun accountFrom(export: AccountExport): List<WalletAccount> {
        require(export.chain in networks) { "${export.chain} is not a Solana network" }
        require(export.kind == AddressKind.DEFAULT) { "Solana accounts have one address kind" }
        require(export.publicKey.curve == Curve.ED25519_SLIP10) { "a Solana key is an ${Curve.ED25519_SLIP10} key" }
        require(isAccountPath(export.signer.path)) { "${export.signer.path} is not a Solana account key, m/44'/501'/N'" }
        val address = addresses.fromPublicKey(export.chain, export.publicKey.bytes, export.kind)
        return listOf(WalletAccount(AccountId.of(export.chain, address), export.signer, export.publicKey, export.kind))
    }

    /** The path of Solana account [index]: m/44'/501'/[index]', every level hardened. */
    fun pathOf(index: Int): List<PathStep> = listOf(PathStep(PURPOSE, true), PathStep(SOLANA_COIN_TYPE, true), PathStep(index, true))

    private const val PURPOSE = 44

    private fun isAccountPath(steps: List<PathStep>): Boolean =
        steps.size == 3 && steps[0] == PathStep(PURPOSE, true) && steps[1] == PathStep(SOLANA_COIN_TYPE, true) && steps[2].hardened
}
