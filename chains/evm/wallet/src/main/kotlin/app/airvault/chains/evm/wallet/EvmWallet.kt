package app.airvault.chains.evm.wallet

import app.airvault.chains.evm.EVM_COIN_TYPE
import app.airvault.chains.evm.EvmAddressCodec
import app.airvault.chains.evm.evmManifest
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
 * The Lumio side of the EVM chain app: its manifest, its address codec, the accounts a pairing gives it, and the networks its accounts are
 * shown on first and by default. Its balances, providers and transaction builder stay in Lumio, which holds their network code.
 */
object EvmWallet : WalletApp {
    override val manifest: ChainManifest = evmManifest

    override val addresses: AddressCodec = EvmAddressCodec

    /** The network an account's native coin is read on, on the testnet or the mainnet side: the one the manifest marks primary there. */
    fun primaryNetwork(testnet: Boolean): ChainId = manifest.networks.single { it.primary && it.testnet == testnet }.chainId

    /** The networks a new vault reads and shows at once, in the manifest's order. */
    fun networksEnabledByDefault(): List<ChainId> = manifest.networks.filter { it.enabledByDefault }.map { it.chainId }

    /**
     * The account of an exported Ethereum key on the network [AccountExport.chain]. An EVM address is the same on every EVM network, so the id
     * names the network the account is shown on. The key must be an uncompressed secp256k1 public key at m/44'/60'/N'/0/0 (the key scope of the
     * manifest). Anything else throws [IllegalArgumentException].
     */
    override fun accountFrom(export: AccountExport): List<WalletAccount> {
        require(export.chain.namespace in manifest.namespaces) { "${export.chain} is not an EVM network" }
        require(export.kind == AddressKind.DEFAULT) { "EVM accounts have one address kind" }
        require(export.publicKey.curve == Curve.SECP256K1) { "an EVM key is a ${Curve.SECP256K1} key" }
        require(isAccountPath(export.signer.path)) { "${export.signer.path} is not an EVM account key, m/44'/60'/N'/0/0" }
        val address = addresses.fromPublicKey(export.chain, export.publicKey.bytes, export.kind)
        return listOf(WalletAccount(AccountId.of(export.chain, address), export.signer, export.publicKey, export.kind))
    }

    /** The path of EVM account [index]: m/44'/60'/[index]'/0/0, the account level hardened and the external chain at 0. */
    fun pathOf(index: Int): List<PathStep> =
        listOf(PathStep(PURPOSE, true), PathStep(EVM_COIN_TYPE, true), PathStep(index, true), PathStep(0, false), PathStep(0, false))

    private const val PURPOSE = 44

    private fun isAccountPath(steps: List<PathStep>): Boolean {
        return steps.size == 5 &&
            steps[0] == PathStep(PURPOSE, true) &&
            steps[1] == PathStep(EVM_COIN_TYPE, true) &&
            steps[2].hardened &&
            steps[3] == PathStep(0, false) &&
            steps[4] == PathStep(0, false)
    }
}
