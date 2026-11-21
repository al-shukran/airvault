package app.lumio.ui.receive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.receive.ReceiveAccount
import app.lumio.receive.ReceiveAnswers
import app.lumio.receive.ReceiveCoin
import app.lumio.receive.ReceiveFlow
import app.lumio.receive.ReceiveNetwork
import app.lumio.receive.ReceiveStep
import app.lumio.receive.monogramOf
import app.lumio.receive.shortAddress
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet

/** The logo or monogram at the start of a row of the Receive sheet. */
private val ROW_LOGO = 36.dp

/** The height and corner of the search field, the proposal's capsule-like field. */
private val SEARCH_HEIGHT = 48.dp
private val SEARCH_CORNER = 24.dp

/**
 * The Receive sheet: the one short task of receiving, asked one question at a time in the same sheet. [start] holds what the Receive already knows, so a
 * Receive opened from an account or an asset skips the questions it can answer. The steps are Choose Asset (only when no coin is known), Choose Network
 * and Choose Account (each only when more than one choice is left), and the Receive sheet itself. [opening] changes each time the sheet opens, so each
 * opening starts from [start] and no earlier answer.
 */
@Composable
internal fun ReceiveFlowSheet(
    visible: Boolean,
    opening: Int,
    start: ReceiveAnswers,
    assets: List<ReceiveCoin>,
    onClose: () -> Unit,
) {
    val flow = remember(opening) { ReceiveFlow(assets, start) }
    // The eyebrow counts the questions this Receive asks: the asset, the network and the account, each unless it is known.
    val asksAsset = start.coin == null
    val total = listOf(asksAsset, start.network == null, start.account == null).count { it }
    val networkNumber = if (asksAsset) 2 else 1
    ClearAirSheet(visible = visible, onClose = onClose) {
        when (val step = flow.step) {
            is ReceiveStep.ChooseAsset -> ReceiveAssetStep(
                assets = step.assets,
                onPick = { coin -> flow.choose(flow.answers.copy(coin = coin)) },
            )

            is ReceiveStep.ChooseNetwork -> ReceiveNetworkStep(
                coin = step.coin,
                label = "Receive ${step.coin.symbol} · Step $networkNumber of $total",
                onBack = if (flow.canGoBack) flow::back else null,
                onPick = { network -> flow.choose(flow.answers.copy(network = network)) },
            )

            is ReceiveStep.ChooseAccount -> ReceiveAccountStep(
                network = step.network,
                label = "Receive ${step.coin.symbol} on ${step.network.label} · Step $total of $total",
                onBack = if (flow.canGoBack) flow::back else null,
                onPick = { account -> flow.choose(flow.answers.copy(account = account)) },
            )

            is ReceiveStep.Receive -> ReceiveSheetStep(
                step = step,
                onBack = if (flow.canGoBack) flow::back else null,
            )
        }
    }
}

/** The body of one step of the Receive sheet: the grabber, the step's [content] in a column that scrolls, and the step's [bottom] action at the foot. */
@Composable
internal fun ReceiveSheetFrame(
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        Grabber()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ClearAirSpacing.gutter),
            content = content,
        )
        if (bottom != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
                content = bottom,
            )
        }
    }
}

/** The grabber at the top of the sheet: a short bar, dimmed. */
@Composable
private fun Grabber() {
    val palette = LocalClearAirPalette.current
    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.ink3.copy(alpha = GRABBER_ALPHA)),
        )
    }
}

/** The first step of a Receive from the global entry: the coin to receive. Each coin is one row, and the held ones come first. */
@Composable
private fun ReceiveAssetStep(assets: List<ReceiveCoin>, onPick: (ReceiveCoin) -> Unit) {
    val palette = LocalClearAirPalette.current
    var query by remember { mutableStateOf("") }
    val shown = assets.filter { it.matches(query) }
    ReceiveSheetFrame {
        Text(
            text = "Receive What?",
            style = ClearAirType.titleL,
            color = palette.ink,
            modifier = Modifier.padding(top = ClearAirSpacing.sm),
        )
        Spacer(Modifier.height(ClearAirSpacing.md))
        ReceiveSearchField(query = query, onQueryChange = { query = it })
        val held = shown.filter { it.held }
        if (held.isNotEmpty()) {
            ClearAirEyebrow(text = "Your Assets")
            held.forEach { coin -> CoinRow(coin = coin, onPick = onPick) }
        }
        val other = shown.filterNot { it.held }
        if (other.isNotEmpty()) {
            ClearAirEyebrow(text = "Other Assets")
            other.forEach { coin -> CoinRow(coin = coin, onPick = onPick) }
        }
    }
}

/** The search field of the asset step. It filters the coins by name and by symbol, and says what it searches when it is empty. */
@Composable
private fun ReceiveSearchField(query: String, onQueryChange: (String) -> Unit) {
    val palette = LocalClearAirPalette.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = ClearAirType.body.copy(color = palette.ink),
        cursorBrush = SolidColor(palette.ink),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SEARCH_HEIGHT)
                    .clip(RoundedCornerShape(SEARCH_CORNER))
                    .background(palette.raised)
                    .padding(horizontal = ClearAirSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearAirIcon(glyph = ClearAirGlyph.SEARCH, color = palette.ink2)
                Spacer(Modifier.width(ClearAirSpacing.xs))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(text = "Search assets", style = ClearAirType.body, color = palette.ink2, maxLines = 1)
                    }
                    field()
                }
            }
        },
    )
}

/** One coin of the asset step: its logo, its name, and its line of networks or accounts. */
@Composable
private fun CoinRow(coin: ReceiveCoin, onPick: (ReceiveCoin) -> Unit) {
    ClearAirRow(
        title = coin.name,
        description = coin.subtitle,
        leading = { AssetIcon(spec = coin.icon, size = ROW_LOGO) },
        onClick = { onPick(coin) },
    )
}

/** The network step: the networks [coin] is native on, with the bridged-asset note under them. */
@Composable
private fun ReceiveNetworkStep(
    coin: ReceiveCoin,
    label: String,
    onBack: (() -> Unit)?,
    onPick: (ReceiveNetwork) -> Unit,
) {
    val palette = LocalClearAirPalette.current
    ReceiveSheetFrame {
        StepHeader(label = label, onBack = onBack)
        Text(
            text = "On Which Network?",
            style = ClearAirType.titleL,
            color = palette.ink,
            modifier = Modifier.padding(top = ClearAirSpacing.sm, bottom = ClearAirSpacing.md),
        )
        coin.networks.forEach { network ->
            ClearAirRow(
                title = network.name,
                leading = { AssetIcon(spec = network.icon, size = ROW_LOGO) },
                onClick = { onPick(network) },
            )
        }
        coin.networkNote?.let { FootNote(text = it) }
    }
}

/** The account step: the accounts that can take the coin on [network], each with its label, group and short address. */
@Composable
private fun ReceiveAccountStep(
    network: ReceiveNetwork,
    label: String,
    onBack: (() -> Unit)?,
    onPick: (ReceiveAccount) -> Unit,
) {
    val palette = LocalClearAirPalette.current
    ReceiveSheetFrame {
        StepHeader(label = label, onBack = onBack)
        Text(
            text = "Into Which Account?",
            style = ClearAirType.titleL,
            color = palette.ink,
            modifier = Modifier.padding(top = ClearAirSpacing.sm, bottom = ClearAirSpacing.md),
        )
        network.accounts.forEach { account ->
            ClearAirRow(
                title = account.name,
                description = accountLine(account),
                leading = { Monogram(initials = monogramOf(account.name)) },
                onClick = { onPick(account) },
            )
        }
        network.accountNote?.let { FootNote(text = it) }
    }
}

/** The eyebrow of a step, with a back arrow in front of it when the step has one to go back to. */
@Composable
private fun StepHeader(label: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            ClearAirIconButton(glyph = ClearAirGlyph.BACK, label = "Back", onClick = onBack)
        }
        ClearAirEyebrow(text = label)
    }
}

/** A line under the choices of a step, in the secondary ink. */
@Composable
private fun FootNote(text: String) {
    val palette = LocalClearAirPalette.current
    Text(
        text = text,
        style = ClearAirType.body,
        color = palette.ink2,
        modifier = Modifier.padding(top = ClearAirSpacing.md),
    )
}

/** The round monogram of an account: its initials on the raised layer, where a logo would be. */
@Composable
private fun Monogram(initials: String) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = Modifier.size(ROW_LOGO).clip(CircleShape).background(palette.raised),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initials, style = ClearAirType.label.copy(fontWeight = FontWeight.Bold), color = palette.ink)
    }
}

/** The line under an account's name: its label, its group, and its short address, as in "Trading · Group DeFi · 0x1020…122b". */
private fun accountLine(account: ReceiveAccount): String =
    listOfNotNull(account.label, account.group?.let { "Group $it" }, shortAddress(account.address)).joinToString(" · ")

private fun ReceiveCoin.matches(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() || name.contains(needle, ignoreCase = true) || symbol.contains(needle, ignoreCase = true)
}

/** The grabber's dimming, the proposal's 55 percent of the ink. */
private const val GRABBER_ALPHA = 0.55f
