package app.lumio.tokens

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An ERC-20 token on one chain: its [contract] (lowercase), the [symbol] and [name] the contract gives, and its [decimals]. The same shape serves
 * the bundled default list and the tokens the owner adds.
 */
@Serializable
data class Token(
    val chainId: Long,
    val contract: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
)

/** The token lists: the bundled defaults, which the app ships, and the owner's own tokens, which the vault keeps. */
object TokenCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class ListFile(val tokens: List<Token> = emptyList())

    /** The tokens of a list file such as `assets/tokens/default-tokens.json`. Throws SerializationException for a file that does not parse. */
    fun parse(text: String): List<Token> = json.decodeFromString(ListFile.serializer(), text).tokens

    /** The tokens read on [chainId]: the defaults, then the owner's. A contract listed twice is read once, from its first entry. */
    fun onChain(defaults: List<Token>, custom: List<Token>, chainId: Long): List<Token> =
        (defaults + custom).filter { it.chainId == chainId }.distinctBy { it.contract }
}

/** The tokens the owner added, kept as one vault record, so they are encrypted under the vault key like the other settings. */
object CustomTokenStore {
    /** The vault record that holds the owner's tokens. */
    const val RECORD = "lumio-custom-tokens"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class StoreFile(val tokens: List<Token> = emptyList())

    /** The owner's tokens in [session], on every chain. A record that does not parse reads as none. */
    fun read(session: VaultSession): List<Token> {
        val bytes = session.getVaultRecord(RECORD) ?: return emptyList()
        return try {
            json.decodeFromString(StoreFile.serializer(), bytes.decodeToString()).tokens
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    /** Adds [token] to [session]. Throws IllegalArgumentException, with the owner's words, when the chain already lists the contract. */
    fun add(session: VaultSession, token: Token) {
        val current = read(session)
        require(current.none { it.chainId == token.chainId && it.contract == token.contract }) { "That token is already in your list." }
        write(session, current + token)
    }

    /** Removes the token at [contract] on [chainId] from [session]. A token that is not there is left as it is. */
    fun remove(session: VaultSession, chainId: Long, contract: String) {
        write(session, read(session).filterNot { it.chainId == chainId && it.contract == contract })
    }

    private fun write(session: VaultSession, tokens: List<Token>) {
        session.putVaultRecord(RECORD, json.encodeToString(StoreFile.serializer(), StoreFile(tokens)).encodeToByteArray())
    }
}
