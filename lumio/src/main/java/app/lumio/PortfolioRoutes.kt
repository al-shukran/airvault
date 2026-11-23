package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.app.addressKeyOf
import app.lumio.chains.NativeBalances
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.PortfolioBoard
import app.lumio.portfolio.PortfolioFilter
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.portfolio.PortfolioScreen
import app.lumio.ui.shell.LumioPageScreen
import app.lumio.ui.transactions.ActivityList
import app.lumio.ui.transactions.AddOnLenslyScreen
import app.lumio.watchlist.WatchedAsset
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The most recent rows that the Portfolio tab shows under its balances. The activity page shows the rest. */
private const val RECENT_ROWS = 3

/**
 * The Portfolio tab: the balances of the side, what they are made of, the filter sentence, and the newest rows of the activity. The board reads the
 * caches when the side or the wallets change, so the tab shows the last read at once.
 */
@Composable
internal fun PortfolioTab(navController: NavHostController, holder: VaultHolder, selection: NetworkSelection, listVersion: Int, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val board = app.lumioVault()?.portfolio
    val mode = selection.mode
    val wallets = pairedWallets(navController, holder, listVersion)
    val display = rememberDisplay(app)
    val transactions = rememberTransactions(app)
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(PortfolioFilter()) }
    LaunchedEffect(board, mode, listVersion, transactions.activityIncludesSpam) {
        board?.readHoldings(mode)
        board?.readActivity(mode, ActivityFilter(), includeSpam = transactions.activityIncludesSpam)
    }
    TabRoute(tab = LumioTab.PORTFOLIO, navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = onLock) {
        PortfolioScreen(
            holdings = board?.holdings,
            filter = filter,
            onFilter = { filter = it },
            walletName = wallets.singleOrNull()?.name ?: "All Wallets",
            accounts = accountCounts(wallets),
            showBalance = display.showAssets,
            hidesSmall = display::hidesSmallBalance,
            testnet = mode == NetworkMode.TESTNET,
            recent = board?.activity?.items.orEmpty().take(RECENT_ROWS),
            names = board?.activity?.names.orEmpty(),
            zone = ZoneId.systemDefault(),
            onOpenActivity = { navController.navigate(LumioRoute.ACTIVITY) },
            onOpenRow = { item -> openTransaction(scope, navController, board, wallets, item) },
        )
    }
}

/**
 * The activity of every account on the side, newest or largest first, with the spam it hides until the owner shows it. A [coin] limits it to that coin,
 * as the Asset page's All link opens it.
 */
@Composable
internal fun ActivityRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    coin: WatchedAsset?,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val board = app.lumioVault()?.portfolio
    val mode = selection.mode
    val wallets = pairedWallets(navController, holder, listVersion)
    val display = rememberDisplay(app)
    val transactions = rememberTransactions(app)
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(ActivityFilter(coin = coin)) }
    // With Hide Spam off, the spam is shown from the start, and the owner has no hidden rows to reveal.
    var showSpam by remember { mutableStateOf(transactions.activityIncludesSpam) }
    LaunchedEffect(board, mode, filter, showSpam) { board?.readActivity(mode, filter, showSpam) }
    val page = board?.activity
    LumioPageScreen(
        title = "Activity",
        backLabel = navController.backLabel(),
        offline = null,
        onBack = { navController.popBackStack() },
        onLock = onLock,
    ) {
        ActivityList(
            items = page?.items,
            filter = filter,
            onFilter = { filter = it },
            families = listOfNotNull(
                ChainFamily.EVM.takeIf { wallets.any { it.evmAddresses.isNotEmpty() } },
                ChainFamily.BTC.takeIf { wallets.any { it.bitcoin.isNotEmpty() } },
            ),
            names = page?.names.orEmpty(),
            spamHidden = page?.spamHidden ?: 0,
            showingSpam = showSpam,
            onShowSpam = { showSpam = true },
            showBalance = display.showAssets,
            zone = ZoneId.systemDefault(),
            nowMs = System.currentTimeMillis(),
            onOpen = { item -> openTransaction(scope, navController, board, wallets, item) },
        )
    }
}

/** The handoff page of an address, which Lensly reads as a code. Done returns to the transaction the address was opened from. */
@Composable
internal fun AddOnLenslyRoute(navController: NavHostController, address: String, edit: Boolean, onLock: () -> Unit) {
    AddOnLenslyScreen(
        address = address,
        edit = edit,
        backLabel = navController.backLabel(),
        onBack = { navController.popBackStack() },
        onLock = onLock,
        onDone = { navController.popBackStack() },
    )
}

/**
 * Opens the page of [item]. The transaction is read before the page is pushed, so the page has it on its first frame. A second tap while the read runs
 * does not push the page twice.
 */
private fun openTransaction(scope: CoroutineScope, navController: NavHostController, board: PortfolioBoard?, wallets: List<PairedWallet>, item: TxFeedItem) {
    val account = item.tx.account
    val txId = item.tx.id
    val walletId = walletIdOf(wallets, account)
    scope.launch {
        board?.readTransaction(walletId, account, txId)
        navController.navigate(LumioRoute.transaction(walletId, account, txId)) { launchSingleTop = true }
    }
}

/** The number of accounts of each chain family across the wallets, for the chain choices. */
private fun accountCounts(wallets: List<PairedWallet>): Map<ChainFamily, Int> = mapOf(
    ChainFamily.EVM to wallets.sumOf { it.evmAddresses.size },
    ChainFamily.BTC to wallets.sumOf { it.bitcoin.size },
    ChainFamily.SOL to wallets.sumOf { it.solana.size },
    ChainFamily.ALGO to wallets.sumOf { it.algorand.size },
)

/** The wallet that holds [account], the address a transaction is on, or the first wallet when none of the wallets holds it. */
internal fun walletIdOf(wallets: List<PairedWallet>, account: String): String {
    val key = addressKeyOf(account)
    return wallets.firstOrNull { wallet -> ownedAddressesOf(wallet).any { addressKeyOf(it) == key } }?.id ?: wallets.firstOrNull()?.id.orEmpty()
}

/**
 * The address of each account of [wallet] on each chain, as the activity reads them: each Ethereum address, the receive address 0 of each Bitcoin
 * account (the address the activity feed queries), and each Solana and Algorand address.
 */
internal fun ownedAddressesOf(wallet: PairedWallet): List<String> =
    wallet.evmAddresses + wallet.bitcoin.map { it.receiveAddress(0) } +
        NativeBalances.addresses(wallet, SOLANA_MAINNET) + NativeBalances.addresses(wallet, ALGORAND_MAINNET)
