package app.lumio.tokens

import app.lumio.evm.ContractReads
import app.lumio.evm.EvmChain

/** Ethereum mainnet as the token tests read it. */
internal val ETHEREUM = EvmChain(chainId = 1L, name = "Ethereum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)

/** A token contract used by the tests, lowercase. */
internal val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

/** A second token contract, lowercase. */
internal val DAI = "0x6b175474e89094c44da98b954eedeac495271d0f"

/** The owner of the balances in the tests. */
internal val OWNER = "0x" + "ab".repeat(20)

/**
 * The ABI words of a reply, written as the chain writes them. Each function returns hex without the 0x prefix, so replies can be joined. A word is
 * 64 hex digits.
 */
internal object Reply {
    fun word(n: Long): String = "%064x".format(n)

    /** A uint256 reply: one word. */
    fun uint(n: Long): String = word(n)

    /** A dynamic string reply: the offset, the length and the UTF-8 bytes padded to a word. */
    fun text(value: String): String {
        val bytes = value.toByteArray()
        val padded = bytes.joinToString("") { "%02x".format(it) }.padEnd(((bytes.size + 31) / 32) * 64, '0')
        return word(32) + word(bytes.size.toLong()) + padded
    }

    /**
     * An aggregate3 reply for [results], each a success flag with its return data (hex without 0x). Each answer is a tuple of a flag and bytes, so it
     * is written with an offset of two words to its bytes, and the answers follow the array's offsets.
     */
    fun aggregate(results: List<Pair<Boolean, String>>): String {
        val elements = results.map { (success, data) ->
            val size = data.length / 2
            word(if (success) 1 else 0) + word(64) + word(size.toLong()) + data.padEnd(((size + 31) / 32) * 64, '0')
        }
        val sb = StringBuilder(word(32)).append(word(results.size.toLong()))
        var offset = results.size * 32L
        for (element in elements) {
            sb.append(word(offset))
            offset += element.length / 2
        }
        elements.forEach { sb.append(it) }
        return "0x$sb"
    }
}

/**
 * Reads the tests give from a function of the contract and the call data. A test answers each call by its target, and [calls] keeps each call,
 * so a test can check which calls were made.
 */
internal class ScriptedReads(private val answer: (to: String, data: String) -> String) : ContractReads {
    val calls = ArrayList<Pair<String, String>>()

    override suspend fun call(chain: EvmChain, to: String, data: String): String {
        calls += to to data
        return answer(to, data)
    }
}
