package app.lumio.evm

import app.airvault.chains.evm.wallet.EvmWallet
import app.lensly.core.vault.VaultSession
import app.lumio.data.net.Networks
import app.lumio.data.rpc.RpcUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A chain the owner added. Its RPC address is in [EvmSettings.rpc]. */
@Serializable
data class CustomChain(val chainId: Long, val name: String, val symbol: String, val testnet: Boolean)

/**
 * Lumio's EVM settings. They are kept as a vault record, so they are encrypted under the vault key: how many addresses of each wallet are
 * shown, which chains are read, the RPC address the owner set for a chain, and the custom chains. A missing field reads as its default.
 */
@Serializable
data class EvmSettings(
    /** How many addresses of each wallet are shown, starting from the first (index 0). */
    val addressCount: Int = DEFAULT_ADDRESS_COUNT,
    /** The chains whose balances are read and shown. */
    val enabled: Set<Long> = DEFAULT_ENABLED,
    /** The RPC address the owner set for a chain, built-in or custom. A chain without one reads from its default. */
    val rpc: Map<Long, String> = emptyMap(),
    val custom: List<CustomChain> = emptyList(),
) {
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
    fun change(session: VaultSession, rpc: EvmRpc, transform: (EvmSettings, EvmRpc) -> EvmSettings): String? = try {
        write(session, transform(read(session), rpc))
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "That change is not valid."
    } catch (e: RpcFailure) {
        e.message ?: "The endpoint did not answer."
    } catch (e: Exception) {
        "The change could not be saved. Try again."
    }

}

/** A custom chain as the owner typed it. Nothing in it is checked until [EvmEdits.addCustom] runs. */
data class CustomChainInput(
    val chainId: String,
    val name: String,
    val symbol: String,
    val rpc: String,
    val testnet: Boolean,
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

    /** [current] with a custom chain added and enabled. The chain ID must be new, and its RPC must answer with that chain ID. */
    fun addCustom(current: EvmSettings, builtInIds: Set<Long>, input: CustomChainInput, rpc: EvmRpc): EvmSettings {
        val chainId = input.chainId.trim().toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Enter a chain ID: a whole number above zero.")
        require(chainId !in builtInIds) { "Chain $chainId is already in the registry. Change its RPC address instead." }
        require(current.custom.none { it.chainId == chainId }) { "Chain $chainId is already added." }
        val name = input.name.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) { "Enter a name of up to $MAX_NAME_LENGTH characters." }
        val symbol = input.symbol.trim()
        require(SYMBOL.matches(symbol)) { "Enter a symbol of 1 to $MAX_SYMBOL_LENGTH letters or digits." }
        val url = RpcUrl.validate(input.rpc, rpc.allowLocalHttp)
        rpc.requireChain(url, chainId)
        return current.copy(
            custom = current.custom + CustomChain(chainId, name, symbol, input.testnet),
            enabled = current.enabled + chainId,
            rpc = current.rpc + (chainId to url),
        )
    }

    /** [current] with the RPC address of [chainId] set to [raw], once the endpoint answers with that chain ID. */
    fun setRpc(current: EvmSettings, chainId: Long, raw: String, rpc: EvmRpc): EvmSettings {
        val url = RpcUrl.validate(raw, rpc.allowLocalHttp)
        rpc.requireChain(url, chainId)
        return current.copy(rpc = current.rpc + (chainId to url))
    }

    /** [current] without an RPC address for [chainId], so the chain reads from its default again. */
    fun resetRpc(current: EvmSettings, chainId: Long): EvmSettings = current.copy(rpc = current.rpc - chainId)

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
