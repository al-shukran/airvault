package app.lumio.tokens

import app.lumio.evm.ContractReads
import app.lumio.evm.EvmChain
import app.lumio.evm.RpcFailure
import app.lumio.evm.SendInput
import java.math.BigInteger

/**
 * Reads a token the owner typed in: its contract must be on the chain, and its decimals(), symbol() and name() must answer as an ERC-20 token does.
 * Each read is a public call of the contract, so the lookup sends only the contract's address to the endpoint.
 */
internal object CustomTokenLookup {
    /** The most decimals Lumio accepts. Real tokens use up to 18. */
    private const val MAX_DECIMALS = 36

    /** The longest symbol Lumio shows, in characters. */
    private const val MAX_SYMBOL = 24

    /** The longest name Lumio keeps, in characters. A name longer than this is cut. */
    private const val MAX_NAME = 64

    private const val EMPTY_REPLY = 2
    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    private val ZERO_ADDRESS = "0x" + "0".repeat(40)

    /**
     * The token at [raw] on [chain]. Throws IllegalArgumentException, with the owner's words, when the text is not a contract address, when no
     * contract is there, or when the contract does not answer as an ERC-20 token. An endpoint failure throws [RpcFailure].
     */
    suspend fun lookup(reads: ContractReads, chain: EvmChain, raw: String): Token {
        val contract = contractOf(raw)
        val decimalsReply = read(reads, chain, contract, Erc20.DECIMALS)
            ?: throw IllegalArgumentException("The contract did not answer decimals(). It may not be an ERC-20 token on ${chain.name}.")
        if (decimalsReply.length <= EMPTY_REPLY) throw IllegalArgumentException("No token found at this address on ${chain.name}.")
        val decimals = try {
            Abi.uint(Abi.bytes(decimalsReply))
        } catch (e: AbiException) {
            throw IllegalArgumentException("The contract does not answer decimals() like an ERC-20 token.")
        }
        if (decimals > BigInteger.valueOf(MAX_DECIMALS.toLong())) throw IllegalArgumentException("The token reports $decimals decimals. Lumio accepts up to $MAX_DECIMALS.")
        val symbol = text(reads, chain, contract, Erc20.SYMBOL)?.trim()
        if (symbol.isNullOrEmpty()) throw IllegalArgumentException("The contract gives no symbol(), so it is not an ERC-20 token.")
        if (symbol.length > MAX_SYMBOL || symbol.any { it.isISOControl() }) throw IllegalArgumentException("The token's symbol is not one Lumio can show.")
        val name = text(reads, chain, contract, Erc20.NAME)?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: symbol
        return Token(chain.chainId, contract, symbol, name, decimals.toInt())
    }

    /**
     * The contract at [raw], trimmed and lowercased. Throws IllegalArgumentException for text that is not an address, for a mixed-case address whose
     * capitals do not match its checksum, or for the zero address.
     */
    internal fun contractOf(raw: String): String {
        val text = raw.trim()
        require(ADDRESS.matches(text)) { "Enter a contract address: 0x and 40 hex digits." }
        require(!SendInput.checksumMismatch(text)) { "This address has a typo: its capital letters don't match its checksum." }
        val contract = text.lowercase()
        require(contract != ZERO_ADDRESS) { "That is the zero address, not a token contract." }
        return contract
    }

    /** The reply of a read, or null when the contract reverts on it. An endpoint failure is rethrown. */
    private suspend fun read(reads: ContractReads, chain: EvmChain, contract: String, selector: String): String? = try {
        reads.call(chain, contract, selector)
    } catch (e: RpcFailure) {
        if (e.reason != RpcFailure.Reason.ERROR) throw e
        null
    }

    /** The text of a read, or null when the read reverts, returns nothing, or returns text that does not decode. */
    private suspend fun text(reads: ContractReads, chain: EvmChain, contract: String, selector: String): String? {
        val reply = read(reads, chain, contract, selector) ?: return null
        if (reply.length <= EMPTY_REPLY) return null
        return try {
            Erc20.text(Abi.bytes(reply))
        } catch (e: AbiException) {
            null
        }
    }
}
