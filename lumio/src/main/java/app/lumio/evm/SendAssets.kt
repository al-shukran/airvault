package app.lumio.evm

import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import java.math.BigInteger

/**
 * A coin that a send can move on [chain]: the native coin ([token] null), or an ERC-20 [token]. [balance] is what the sending address holds of the
 * token, in its base units, or null when no balance is held for it. The native coin's balance is read when the send is prepared, not here.
 */
class SendAsset(val chain: EvmChain, val token: Token?, val balance: BigInteger?) {
    /** The symbol of the coin: the token's, or the chain's native symbol. */
    val symbol: String get() = token?.symbol ?: chain.symbol

    /** The decimal places of the coin: the token's, or the chain's native decimals. */
    val decimals: Int get() = token?.decimals ?: chain.decimals
}

/** The coins a send from one address can move, for the asset picker of the send form. */
object SendAssets {
    /**
     * The coins a send can move on [chain]: the native coin first, then each of [tokens] that [held] shows the address holding, with a positive
     * balance. [preferred] names the token a send starts on, and it is listed even with no balance, so the form can say why it cannot be sent.
     * Holdings of other chains are not listed.
     */
    fun onChain(chain: EvmChain, tokens: List<Token>, held: List<TokenHolding>, preferred: String?): List<SendAsset> {
        val holding = held.filter { it.chain.chainId == chain.chainId && it.amount.signum() > 0 }
        val listed = holding.map { it.token.contract }.toSet()
        val preferredToken = tokens.firstOrNull { it.contract == preferred && it.contract !in listed }
        return listOf(SendAsset(chain, null, null)) +
            holding.map { SendAsset(chain, it.token, it.amount) } +
            listOfNotNull(preferredToken?.let { SendAsset(chain, it, null) })
    }
}
