package app.airvault.core.wallet

import app.airvault.core.model.AccountId
import app.airvault.core.model.AddressKind
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef

/**
 * One account of a paired wallet (spec 3.3.1): its CAIP-10 [id], the key and path that sign for it, and its address kind. Two
 * accounts with the same [id] are the same account.
 */
data class WalletAccount(
    val id: AccountId,
    val signer: SignerRef,
    val publicKey: PublicKey,
    val kind: AddressKind,
)
