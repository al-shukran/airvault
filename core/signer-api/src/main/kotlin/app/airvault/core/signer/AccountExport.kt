package app.airvault.core.signer

import app.airvault.core.model.AddressKind
import app.airvault.core.model.ChainId

/**
 * The public data of one account that a signer exports for pairing (spec 7.5): the network it is shown on, the key and path
 * that sign for it, and its address kind. Wallet apps turn it into accounts with `WalletApp.accountFrom`.
 */
class AccountExport(val chain: ChainId, val signer: SignerRef, val publicKey: PublicKey, val kind: AddressKind)
