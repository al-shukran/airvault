package app.lumio.tokens

import app.lumio.evm.ContractReads
import app.lumio.evm.EvmChain
import app.lumio.evm.RpcFailure
import java.math.BigInteger

/** The balances of ERC-20 tokens, read from a chain's RPC pool. */
internal object TokenBalances {
    /**
     * The balance of [owner] in each of [contracts] on [chain], in the token's base units. One Multicall3 call reads them all. A chain without
     * Multicall3 answers that call with no data, or with an error, and then each contract is read with its own balanceOf call.
     *
     * A contract that does not answer, or reverts, maps to null, so one bad token never hides the others. An endpoint failure, such as an
     * unreachable or refused endpoint, throws [RpcFailure] and the whole read fails.
     */
    suspend fun read(reads: ContractReads, chain: EvmChain, owner: String, contracts: List<String>): Map<String, BigInteger?> {
        val unique = contracts.distinct()
        if (unique.isEmpty()) return emptyMap()
        val reply = try {
            reads.call(chain, Multicall3.ADDRESS, Multicall3.aggregate3(unique.map { Multicall3.Call(it, allowFailure = true, Erc20.balanceOf(owner)) }))
        } catch (e: RpcFailure) {
            if (e.reason != RpcFailure.Reason.ERROR) throw e
            null
        }
        if (reply == null || reply.length <= EMPTY_REPLY) return individually(reads, chain, owner, unique)
        val results = try {
            Multicall3.decodeAggregate3(reply, unique.size)
        } catch (e: AbiException) {
            throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent an answer that does not decode.")
        }
        return unique.zip(results).associate { (contract, result) ->
            contract to if (result.success && result.data.size >= Abi.WORD) Abi.uint(result.data) else null
        }
    }

    private suspend fun individually(reads: ContractReads, chain: EvmChain, owner: String, contracts: List<String>): Map<String, BigInteger?> {
        val balances = LinkedHashMap<String, BigInteger?>()
        for (contract in contracts) {
            balances[contract] = try {
                val reply = reads.call(chain, contract, Erc20.balanceOf(owner))
                if (reply.length <= EMPTY_REPLY) null else Abi.uint(Abi.bytes(reply))
            } catch (e: RpcFailure) {
                if (e.reason != RpcFailure.Reason.ERROR) throw e
                null
            } catch (e: AbiException) {
                null
            }
        }
        return balances
    }

    /** The length of hex text for an empty reply: `0x`. */
    private const val EMPTY_REPLY = 2
}
