package app.lumio.send.algorand

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.theme.LenslySpacing
import app.lumio.LumioApp
import app.lumio.LumioRoute
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.network.NetworkMode
import app.lumio.onVault
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.ui.kit.LumioIsland
import app.lumio.ui.kit.LumioRow
import app.lumio.ui.kit.LumioTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An asset the Add asset screen lists on one network without a warning. */
internal data class VerifiedAsset(val assetId: ULong, val symbol: String, val testnet: Boolean)

/** The verified assets: USDC on testnet and on mainnet. */
internal val VERIFIED_ASSETS = listOf(
    VerifiedAsset(assetId = 10458941UL, symbol = "USDC", testnet = true),
    VerifiedAsset(assetId = 31566704UL, symbol = "USDC", testnet = false),
)

/** The detail of an asset ID that the verified list does not hold: the owner checks the ID before the opt-in. */
private const val UNVERIFIED_DETAIL = "Not on the verified list. Check the ID before you add it."

/** One row of the Add asset list: the asset's [title], its [detail], and whether the account has [added] it already. */
data class AddAssetRow(val assetId: ULong, val title: String, val detail: String, val added: Boolean)

/** The rows that the Add asset list shows for a search, as plain data, so that the rules are tested without a screen. */
object AddAssetRows {
    /**
     * The rows for [query] on [network]: each verified asset of the network whose symbol contains the query or whose ID starts with it, then the asset
     * whose ID the query types, when the query is a positive whole number that the verified list does not hold. An empty query lists every verified
     * asset. A row is added when [holdings] holds its asset, zero balances included.
     */
    fun rows(network: NetworkId, holdings: List<AsaHolding>, query: String): List<AddAssetRow> {
        val text = query.trim()
        val verified = VERIFIED_ASSETS.filter { it.testnet == network.testnet }
        val matches = verified
            .filter { it.symbol.contains(text, ignoreCase = true) || it.assetId.toString().startsWith(text) }
            .map { AddAssetRow(it.assetId, it.symbol, "Asset ID ${it.assetId}", AlgorandAssets.optedIn(it.assetId, holdings)) }
        val typed = text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toULongOrNull()?.takeIf { it > 0UL }
            ?.takeIf { id -> verified.none { it.assetId == id } }
            ?.let { AddAssetRow(it, "Asset $it", UNVERIFIED_DETAIL, AlgorandAssets.optedIn(it, holdings)) }
        return matches + listOfNotNull(typed)
    }
}

/**
 * The Add asset screen of an Algorand account: a search over the verified assets of the network, or the asset ID the owner types. A row that the account
 * has not added opens its opt-in. [reading] holds the rows until the account's assets have been read.
 */
@Composable
internal fun AlgorandAddAssetScreen(
    rows: List<AddAssetRow>,
    query: String,
    reading: Boolean,
    onQuery: (String) -> Unit,
    onPick: (AddAssetRow) -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(title = "Add asset", onBack = onBack) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search, or enter an asset ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LenslySpacing.md))
        if (reading) {
            Text("Reading the network", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (rows.isEmpty()) {
            Text(
                "No asset matches. Type an asset ID to add it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LumioIsland {
            rows.forEach { row ->
                val trailing: (@Composable () -> Unit)? = if (row.added) ({ LumioTag(text = "Added") }) else null
                LumioRow(
                    title = row.title,
                    description = row.detail,
                    trailing = trailing,
                    onClick = if (row.added || reading) null else ({ onPick(row) }),
                )
            }
        }
    }
}

/**
 * The Add asset screen of the Algorand account [accountIndex] of [walletId]. The account's opted-in assets are read first, then a row opens the opt-in of
 * its asset, which is signed through Lensly like a send.
 */
@Composable
internal fun AlgorandAddAssetRoute(
    navController: NavHostController,
    holder: VaultHolder,
    mode: NetworkMode,
    walletId: String,
    accountIndex: Int,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val paired by produceState<PairedWallet?>(initialValue = null, session, walletId) {
        value = onVault(null) { PairedWallets.list(session).firstOrNull { it.id == walletId } }
    }
    val wallet = paired ?: return
    val signer = wallet.algorand.getOrNull(accountIndex) ?: return
    val address = AlgorandAccount.addressOf(signer)
    val network = if (mode.holds(ALGORAND_TESTNET.testnet)) ALGORAND_TESTNET else ALGORAND_MAINNET
    val scheduler = app.nativeSchedulerFor(session)
    // A read that fails lists the verified assets as not added, so the owner can still choose one; the opt-in reads the network again.
    val holdings by produceState<List<AsaHolding>?>(initialValue = null, network, address) {
        value = runCatching { withContext(Dispatchers.IO) { optedInAssets(scheduler, network, address) } }.getOrElse { emptyList() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    AlgorandAddAssetScreen(
        rows = AddAssetRows.rows(network, holdings.orEmpty(), query),
        query = query,
        reading = holdings == null,
        onQuery = { query = it },
        onPick = { row -> navController.navigate(LumioRoute.algorandOptIn(walletId, accountIndex, row.assetId)) },
        onBack = { navController.popBackStack() },
    )
}
