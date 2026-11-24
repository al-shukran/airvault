package app.lumio.evm

import app.airvault.chains.evm.wallet.EvmWallet
import app.lensly.core.vault.VaultSession
import app.lumio.data.net.Networks
import app.lumio.data.rpc.RpcUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A chain the owner added. Its RPC addresses are in [EvmSettings.rpc]. [explorer] is its block explorer, when the owner gave one. */
@Serializable
data class CustomChain(val chainId: Long, val name: String, val symbol: String, val testnet: Boolean, val explorer: String? = null)

/**
 * The RPC addresses the owner set for one chain. [custom] holds the addresses the owner added, in the order added. [active] is the one the chain reads
 * from, or null for the built-in address. [active] is always null or one of [custom].
 */
@Serializable
data class ChainRpcs(val custom: List<String> = emptyList(), val active: String? = null)

/**
 * Lumio's EVM settings. They are kept as a vault record, so they are encrypted under the vault key: how many addresses of each wallet are
 * shown, which chains are read, the RPC addresses the owner set for each chain, and the custom chains. A missing field reads as its default.
 */
@Serializable
data class EvmSettings(
    /** How many addresses of each wallet are shown, starting from the first (index 0). */
    val addressCount: Int = DEFAULT_ADDRESS_COUNT,
    /** The chains whose balances are read and shown. */
    val enabled: Set<Long> = DEFAULT_ENABLED,
    /** The RPC addresses the owner set for each chain. A chain without one in use reads from its built-in address. */
    val rpc: Map<Long, ChainRpcs> = emptyMap(),
    val custom: List<CustomChain> = emptyList(),
) {
    /** The RPC address that [chainId] reads from, or null when it reads from its built-in address. */
    fun activeRpc(chainId: Long): String? = rpc[chainId]?.active

    /** The RPC address in use for each chain that has one, keyed by chain ID as text, the form the provider settings use for their chain URLs. */
    fun activeRpcUrls(): Map<String, String> = rpc.mapNotNull { (chainId, rpcs) -> rpcs.active?.let { chainId.toString() to it } }.toMap()

    companion object {
        const val DEFAULT_ADDRESS_COUNT = 5
        const val MAX_ADDRESS_COUNT = 20

        /**
         * The networks the EVM manifest marks enabled by default: Ethereum mainnet, then the testnets, Monad testnet and Sepolia. A new vault shows
         * its balances on a mainnet and on testnets at once. Sepolia is in the registry of debug builds only, so a release build lists no
         * Sepolia chain and reads none. Monad testnet is in the release registry too.
         */
        val DEFAULT_ENABLED: Set<Long> = EvmWallet.networksEnabledByDefault().map { Networks.evmIdOf(it) }.toSet()

        /** The vault record that holds these settings. */
        const val RECORD = "lumio-evm-settings"
    }
}

/** Reads and writes [EvmSettings] as the vault record [EvmSettings.RECORD]. */
object EvmSettingsStore {
    private val json = Json

    /** The settings stored in [session], or the defaults when none are stored or the record does not parse. */
    fun read(session: VaultSession): EvmSettings {
        val bytes = session.getVaultRecord(EvmSettings.RECORD) ?: return EvmSettings()
        return try {
            json.decodeFromString(EvmSettings.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            EvmSettings()
        }
    }

    /** Saves [settings]. */
    fun write(session: VaultSession, settings: EvmSettings) {
        session.putVaultRecord(EvmSettings.RECORD, json.encodeToString(EvmSettings.serializer(), settings).encodeToByteArray())
    }

    /**
     * Runs [transform] on the stored settings, and writes its result only when it succeeds. Returns null when the change is saved, or the owner's
     * words when it is refused. A refused change leaves the stored settings as they were, so an RPC address that did not check keeps the one it
     * had. [transform] may block on the endpoint, so the caller runs this off the main thread.
     */
    fun change(session: VaultSession, rpc: EvmRpc, transform: (EvmSettings, EvmRpc) -> EvmSettings): String? = attempt(session, rpc, transform)?.message

    /** [change] for a new custom network: a refusal names the form field it concerns. Null when the network is saved. */
    fun addCustom(session: VaultSession, rpc: EvmRpc, builtIn: Set<Long>, input: CustomChainInput): EvmRefusal? =
        attempt(session, rpc) { current, endpoint -> EvmEdits.addCustom(current, builtIn, input, endpoint) }

    /** Runs one change at a time, so a change made while another checks its endpoint is kept, not overwritten by that check's write. */
    @Synchronized
    private fun attempt(session: VaultSession, rpc: EvmRpc, transform: (EvmSettings, EvmRpc) -> EvmSettings): EvmRefusal? = try {
        write(session, transform(read(session), rpc))
        null
    } catch (e: EvmRefusal) {
        e
    } catch (e: IllegalArgumentException) {
        EvmRefusal(null, e.message ?: "That change is not valid.")
    } catch (e: RpcFailure) {
        EvmRefusal(CustomNetworkField.RPC, e.message ?: "The endpoint did not answer.")
    } catch (e: Exception) {
        EvmRefusal(null, "The change could not be saved. Try again.")
    }
}

/** The fields of the Add Custom Network form. A refusal names the field it concerns. */
enum class CustomNetworkField { NAME, RPC, CHAIN_ID, SYMBOL, EXPLORER }

/** A change that is refused, with the owner's words. [field] is the form field it concerns, or null when it concerns no single field. */
class EvmRefusal(val field: CustomNetworkField?, message: String) : IllegalArgumentException(message)

/** A custom chain as the owner typed it. Nothing in it is checked until [EvmEdits.addCustom] runs. [explorer] may be empty. */
data class CustomChainInput(
    val chainId: String,
    val name: String,
    val symbol: String,
    val rpc: String,
    val testnet: Boolean,
    val explorer: String = "",
)

/**
 * The changes the settings screen can make. Each returns the settings with the change applied. It throws IllegalArgumentException, with the
 * owner's words, for input that is not valid, and [RpcFailure] when the endpoint does not serve the chain. These calls block on the endpoint,
 * so the caller runs them off the main thread.
 */
object EvmEdits {
    const val MAX_NAME_LENGTH = 40
    private const val MAX_SYMBOL_LENGTH = 10
    private val SYMBOL = Regex("[A-Za-z0-9]{1,$MAX_SYMBOL_LENGTH}")

    /**
     * [current] with a custom chain added and enabled. The chain ID must be new, its RPC must answer with that chain ID, and an explorer, when
     * given, must be an https address. Each refusal names the field it concerns, and an endpoint that serves another chain is an [RpcFailure].
     */
    fun addCustom(current: EvmSettings, builtInIds: Set<Long>, input: CustomChainInput, rpc: EvmRpc): EvmSettings {
        val chainId = input.chainId.trim().toLongOrNull()?.takeIf { it > 0 }
            ?: refuse(CustomNetworkField.CHAIN_ID, "Enter a chain ID: a whole number above zero.")
        if (chainId in builtInIds) refuse(CustomNetworkField.CHAIN_ID, "Chain $chainId is already in the registry. Change its RPC address instead.")
        if (current.custom.any { it.chainId == chainId }) refuse(CustomNetworkField.CHAIN_ID, "Chain $chainId is already added.")
        val name = input.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) refuse(CustomNetworkField.NAME, "Enter a name of up to $MAX_NAME_LENGTH characters.")
        val symbol = input.symbol.trim()
        if (!SYMBOL.matches(symbol)) refuse(CustomNetworkField.SYMBOL, "Enter a symbol of 1 to $MAX_SYMBOL_LENGTH letters or digits.")
        val explorer = input.explorer.trim().takeIf { it.isNotEmpty() }?.let(::explorerOf)
        val url = try {
            RpcUrl.validate(input.rpc, rpc.allowLocalHttp)
        } catch (e: IllegalArgumentException) {
            refuse(CustomNetworkField.RPC, e.message ?: RpcUrl.REFUSED)
        }
        rpc.requireChain(url, chainId)
        return current.copy(
            custom = current.custom + CustomChain(chainId, name, symbol, input.testnet, explorer),
            enabled = current.enabled + chainId,
            rpc = current.rpc + (chainId to ChainRpcs(custom = listOf(url), active = url)),
        )
    }

    /** [raw] as the explorer address Lumio keeps: an https address, normalized. */
    private fun explorerOf(raw: String): String {
        val url = raw.toHttpUrlOrNull()
        if (url == null || url.scheme != "https") refuse(CustomNetworkField.EXPLORER, "Use an https address for the explorer, or leave it empty.")
        return url.toString()
    }

    private fun refuse(field: CustomNetworkField, message: String): Nothing = throw EvmRefusal(field, message)

    /** [current] with [raw] added to the custom RPC addresses of [chainId] and in use, once the endpoint answers with that chain ID. */
    fun setRpc(current: EvmSettings, chainId: Long, raw: String, rpc: EvmRpc): EvmSettings {
        val url = RpcUrl.validate(raw, rpc.allowLocalHttp)
        rpc.requireChain(url, chainId)
        return current.withRpcs(chainId) { it.copy(custom = if (url in it.custom) it.custom else it.custom + url, active = url) }
    }

    /** [current] with the built-in RPC address of [chainId] in use. Its custom addresses stay in the list. */
    fun resetRpc(current: EvmSettings, chainId: Long): EvmSettings = current.withRpcs(chainId) { it.copy(active = null) }

    /** [current] with [url], one of the custom RPC addresses of [chainId], in use. */
    fun useRpc(current: EvmSettings, chainId: Long, url: String): EvmSettings {
        require(url in (current.rpc[chainId]?.custom ?: emptyList())) { "That address is not one of the chain's custom RPCs." }
        return current.withRpcs(chainId) { it.copy(active = url) }
    }

    /** [current] without the custom RPC address [url] of [chainId]. When [url] was in use, the built-in address is in use instead. */
    fun removeRpc(current: EvmSettings, chainId: Long, url: String): EvmSettings = current.withRpcs(chainId) {
        it.copy(custom = it.custom - url, active = if (it.active == url) null else it.active)
    }

    /** [current] with the RPC addresses of [chainId] changed by [change]. A chain left with none drops its entry, so it reads as it does by default. */
    private fun EvmSettings.withRpcs(chainId: Long, change: (ChainRpcs) -> ChainRpcs): EvmSettings {
        val next = change(rpc[chainId] ?: ChainRpcs())
        return copy(rpc = if (next == ChainRpcs()) rpc - chainId else rpc + (chainId to next))
    }

    fun setEnabled(current: EvmSettings, chainId: Long, on: Boolean): EvmSettings =
        current.copy(enabled = if (on) current.enabled + chainId else current.enabled - chainId)

    /** [current] without the custom chain [chainId], its RPC address and its enabled flag. */
    fun removeCustom(current: EvmSettings, chainId: Long): EvmSettings = current.copy(
        custom = current.custom.filter { it.chainId != chainId },
        enabled = current.enabled - chainId,
        rpc = current.rpc - chainId,
    )

    fun setAddressCount(current: EvmSettings, count: Int): EvmSettings {
        require(count in 1..EvmSettings.MAX_ADDRESS_COUNT) { "Show 1 to ${EvmSettings.MAX_ADDRESS_COUNT} addresses." }
        return current.copy(addressCount = count)
    }
}
