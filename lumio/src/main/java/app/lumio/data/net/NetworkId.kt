package app.lumio.data.net

import app.airvault.core.model.ChainId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * One network: its CAIP-2 [chain] id and whether it is a [testnet]. A provider maps the network to its own API, and the registry decides which
 * networks exist. [name] is the chain name that providers and cache keys have always used: "mainnet", "testnet4", "mainnet-beta", or an EVM
 * chain id such as "1".
 */
@Serializable
data class NetworkId(@Serializable(with = ChainIdSerializer::class) val chain: ChainId, val testnet: Boolean) {
    val name: String get() = Networks.nameOf(chain)

    /** The chain family as stored ids have always written it: BTC, EVM, SOL or ALGO. */
    val idPrefix: String get() = when {
        isBtc -> "BTC"
        isEvm -> "EVM"
        isSol -> "SOL"
        isAlgo -> "ALGO"
        else -> error("Lumio has no id prefix for $chain")
    }

    val isBtc: Boolean get() = chain.namespace == Networks.BIP122
    val isEvm: Boolean get() = chain.namespace == Networks.EIP155
    val isSol: Boolean get() = chain.namespace == Networks.SOLANA
    val isAlgo: Boolean get() = chain.namespace == Networks.ALGORAND
}

/** The networks Lumio reads, by CAIP-2 id (spec 4). The ids come from the ChainAgnostic namespaces registry, except [BTC_TESTNET4]. */
object Networks {
    const val BIP122 = "bip122"
    const val EIP155 = "eip155"
    const val SOLANA = "solana"
    const val ALGORAND = "algorand"

    val BTC_MAINNET = ChainId("bip122:000000000019d6689c085ae165831e93")

    /**
     * Not on the registry page, which lists only Testnet3. It follows the registry's bip122 rule (the first 32 hex characters of the genesis hash),
     * with the Testnet4 genesis hash that Bitcoin Core asserts. The owner must confirm it.
     */
    val BTC_TESTNET4 = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae")
    val SOL_MAINNET = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp")
    val SOL_DEVNET = ChainId("solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1")
    val ALGO_MAINNET = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")
    val ALGO_TESTNET = ChainId("algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDe")

    /** The EVM network with chain id [id]. */
    fun evm(id: Long): ChainId = ChainId("$EIP155:$id")

    /** The chain name of [chain]: the names that providers and cache keys have always used. */
    fun nameOf(chain: ChainId): String = when (chain) {
        BTC_MAINNET -> "mainnet"
        BTC_TESTNET4 -> "testnet4"
        SOL_MAINNET -> "mainnet-beta"
        SOL_DEVNET -> "devnet"
        ALGO_MAINNET -> "mainnet"
        ALGO_TESTNET -> "testnet"
        else -> chain.caip2.substringAfter(':')
    }
}

/** A [ChainId] stored as its CAIP-2 text. */
object ChainIdSerializer : KSerializer<ChainId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.lumio.ChainId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ChainId) = encoder.encodeString(value.caip2)

    override fun deserialize(decoder: Decoder): ChainId = ChainId(decoder.decodeString())
}
