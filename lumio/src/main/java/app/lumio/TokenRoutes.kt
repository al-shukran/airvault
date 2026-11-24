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
import app.lumio.pair.PairedAccount
import app.lumio.pair.PairedWallets
import app.lumio.tokens.CustomTokenLookup
import app.lumio.tokens.CustomTokenStore
import app.lumio.tokens.Token
import app.lumio.tokens.TokenBalances
import app.lumio.tokens.holdingText
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.tokens.AddTokenScreen
import app.lumio.ui.tokens.TokenHolding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What [AddTokenRoute] reads from the vault: the chain and its bundled tokens with the owner's own, every chain a token can be added on (for Change),
 * and the first Ethereum account of the active wallet, which holds the token. A null [chain] means the chain is not listed.
 */
private class TokenListState(
    val chain: EvmChain?,
    val chains: List<EvmChain>,
    val defaults: List<Token>,
    val custom: List<Token>,
    val account: PairedAccount?,
)

/** The owner's words for a failed lookup or save: the message of a refusal or a failed read, and a plain line for anything else. */
private fun ownerWords(error: Throwable): String = when (error) {
    is IllegalArgumentException, is RpcFailure -> error.message ?: "The token could not be read. Try again."
    else -> "The token could not be read. Try again."
}

/** The tokens of [chainId] as the screen shows them. Reads the vault, so the caller runs it off the main thread. */
private fun loadTokens(app: LumioApp, session: VaultSession, chainId: Long, selection: NetworkSelection): TokenListState {
    val settings = EvmSettingsStore.read(session)
    val chains = EvmCatalog.chains(app.registry, settings)
    val custom = CustomTokenStore.read(session)
    val wallet = PairedWallets.list(session).active(selection) { it.id }
    return TokenListState(
        chain = chains.firstOrNull { it.chainId == chainId },
        chains = chains,
        defaults = app.defaultTokens.filter { it.chainId == chainId },
        custom = custom.filter { it.chainId == chainId },
        account = wallet?.evmAccounts?.firstOrNull(),
    )
}

/**
 * The tokens of one chain: the owner pastes a contract address, the lookup reads the token from the chain, and the owner adds it or drops it.
 * Each change is written to the vault when the owner confirms it. A lookup that fails keeps the screen open with the owner's words for why.
 */
@Composable
internal fun AddTokenRoute(
    navController: NavHostController,
    holder: VaultHolder,
    chainId: Long,
    selection: NetworkSelection,
    onLock: () -> Unit,
) {
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
    var holding by remember { mutableStateOf<TokenHolding?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var choosingNetwork by remember { mutableStateOf(false) }
    val state by produceState<TokenListState?>(initialValue = null, session, chainId, version) {
        value = if (session == null) {
            null
        } else {
            withContext(Dispatchers.IO) { runCatching { loadTokens(app, session, chainId, selection) }.getOrNull() }
        }
    }
    val current = state ?: return
    val chain = current.chain
    if (chain == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    // Reads what the owner's account holds of the found token. A read that fails leaves the holding row out, and the rest of the page still works.
    LaunchedEffect(found) {
        holding = null
        val token = found ?: return@LaunchedEffect
        val account = current.account ?: return@LaunchedEffect
        val open = session ?: return@LaunchedEffect
        val amount = withContext(Dispatchers.IO) {
            runCatching { TokenBalances.balance(app.evmReadsFor(open), chain, account.address, token.contract) }.getOrNull()
        }
        holding = amount?.let { TokenHolding(label = account.name?.let { "$it Holds" } ?: "Holds", amount = holdingText(it, token)) }
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
        holding = holding,
        problem = problem,
        offline = rememberSettingsOffline(),
        onAddress = {
            address = it
            found = null
            problem = null
        },
        onLookUp = ::lookUp,
        onAdd = ::add,
        onCancel = { found = null },
        onChangeNetwork = { choosingNetwork = true },
        onRemove = ::remove,
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )

    // Change picks another chain. The page for that chain replaces this one, so the typed address and the found token do not carry over.
    ClearAirSheet(visible = choosingNetwork, onClose = { choosingNetwork = false }) {
        ClearAirChoiceSheet(
            title = "Network",
            groups = listOf(
                ChoiceGroup(
                    label = null,
                    rows = current.chains.map { option ->
                        ChoiceRow(
                            title = option.name,
                            selected = option.chainId == chainId,
                            onPick = {
                                choosingNetwork = false
                                navController.navigate(LumioRoute.tokens(option.chainId)) { popUpTo(LumioRoute.TOKENS) { inclusive = true } }
                            },
                        )
                    },
                ),
            ),
        )
    }
}
