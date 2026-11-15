package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettingsStore
import app.lumio.evm.RpcFailure
import app.lumio.tokens.CustomTokenLookup
import app.lumio.tokens.CustomTokenStore
import app.lumio.tokens.Token
import app.lumio.ui.tokens.AddTokenScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The chain of [AddTokenRoute] with its bundled tokens and the owner's own tokens on it. A null [chain] means the chain is not listed. */
private class TokenListState(val chain: EvmChain?, val defaults: List<Token>, val custom: List<Token>)

/** The owner's words for a failed lookup or save: the message of a refusal or a failed read, and a plain line for anything else. */
private fun ownerWords(error: Throwable): String = when (error) {
    is IllegalArgumentException, is RpcFailure -> error.message ?: "The token could not be read. Try again."
    else -> "The token could not be read. Try again."
}

/** The tokens of [chainId] as the screen shows them. Reads the vault, so the caller runs it off the main thread. */
private fun loadTokens(app: LumioApp, session: VaultSession, chainId: Long): TokenListState {
    val settings = EvmSettingsStore.read(session)
    val chain = EvmCatalog.chains(app.registry, settings).firstOrNull { it.chainId == chainId }
    val custom = CustomTokenStore.read(session)
    return TokenListState(
        chain = chain,
        defaults = app.defaultTokens.filter { it.chainId == chainId },
        custom = custom.filter { it.chainId == chainId },
    )
}

/**
 * The tokens of one chain: the owner pastes a contract address, the lookup reads the token from the chain, and the owner adds it or drops it.
 * Each change is written to the vault when the owner confirms it. A lookup that fails keeps the screen open with the owner's words for why.
 */
@Composable
internal fun AddTokenRoute(navController: NavHostController, holder: VaultHolder, chainId: Long) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Token?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val state by produceState<TokenListState?>(initialValue = null, session, chainId, version) {
        value = if (session == null) {
            null
        } else {
            withContext(Dispatchers.IO) { runCatching { loadTokens(app, session, chainId) }.getOrNull() }
        }
    }
    val current = state ?: return
    val chain = current.chain
    if (chain == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    // Reads the token at the typed address from the chain. A token the owner already has is named as such, and not offered again.
    fun lookUp() {
        val open = session ?: return
        if (busy) return
        busy = true
        problem = null
        found = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { CustomTokenLookup.lookup(app.evmReadsFor(open), chain, address) }
            }
            busy = false
            outcome.onSuccess { token ->
                if ((current.defaults + current.custom).any { it.contract == token.contract }) {
                    problem = "${token.symbol} is already in your list."
                } else {
                    found = token
                }
            }.onFailure { problem = ownerWords(it) }
        }
    }

    // Saves the token the lookup found. The vault refuses a token the chain already lists, and that refusal is shown.
    fun add(token: Token) {
        val open = session ?: return
        if (busy) return
        busy = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { CustomTokenStore.add(open, token) }.exceptionOrNull() }
            busy = false
            if (failure == null) {
                found = null
                address = ""
                problem = null
                version++
            } else {
                problem = ownerWords(failure)
            }
        }
    }

    fun remove(token: Token) {
        val open = session ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { CustomTokenStore.remove(open, token.chainId, token.contract) } }
            version++
        }
    }

    AddTokenScreen(
        chainName = chain.name,
        defaults = current.defaults,
        custom = current.custom,
        address = address,
        busy = busy,
        found = found,
        problem = problem,
        onAddress = {
            address = it
            found = null
            problem = null
        },
        onLookUp = ::lookUp,
        onAdd = ::add,
        onCancel = { found = null },
        onRemove = ::remove,
        onBack = { navController.popBackStack() },
    )
}
