package app.lensly.ui.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.R
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.components.BackdropLogo
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.components.chainIconDrawables
import app.lensly.ui.components.chainIconsNeedingBackdrop
import app.lensly.ui.components.tokenIconDrawables
import app.lensly.ui.components.tokenIconsNeedingBackdrop
import app.lensly.ui.theme.LenslyBlack

/**
 * Debug-only grid of every icon: each chain logo, each token logo, the unknown-token icon, the Lensly logo and the
 * adaptive launcher icon, masked as a circle. Each cell is labelled with its drawable name.
 */
@Composable
internal fun IconsPreview() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(LenslyBlack),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        section("CHAINS")
        items(chainIconDrawables.entries.sortedBy { it.key }) { entry ->
            IconCell(resource = entry.value, label = entry.key, backdrop = entry.key in chainIconsNeedingBackdrop)
        }
        section("TOKENS")
        items(tokenIconDrawables.entries.sortedBy { it.key }) { entry ->
            IconCell(resource = entry.value, label = entry.key, backdrop = entry.key in tokenIconsNeedingBackdrop)
        }
        section("PEGGED AND BRIDGED: TOKEN LOGO WITH CHAIN BADGE")
        items(stackedSamples) { sample -> StackedCell(sample) }
        section("APP AND FALLBACK")
        item { IconCell(resource = app.lensly.shared.R.drawable.ic_unknown_token, label = "ic_unknown_token") }
        item { IconCell(resource = R.drawable.logo_lensly, label = "logo_lensly") }
        item { LauncherCell() }
    }
}

/** A pegged or bridged copy, drawn with the logo of its chain as a badge. */
private class StackedSample(val label: String, val token: TokenEntry, val chain: NetworkEntry)

private val bnbSmartChain = NetworkEntry(
    chainId = 56L,
    name = "BNB Smart Chain",
    nativeSymbol = "BNB",
    feeModel = FeeModel.L1,
    explorerName = "BscScan",
    testnet = false,
    icon = "chain_binance_smart_chain",
)

private val avalancheChain = NetworkEntry(
    chainId = 43114L,
    name = "Avalanche C-Chain",
    nativeSymbol = "AVAX",
    feeModel = FeeModel.L1,
    explorerName = "Snowtrace",
    testnet = false,
    icon = "chain_avalanche",
)

private val stackedSamples = listOf(
    StackedSample(
        label = "Binance Pegged USDT",
        token = TokenEntry(
            chainId = 56L,
            contract = "0x55d398326f99059ff775485246999027b3197955",
            symbol = "USDT",
            name = "Binance Pegged USDT",
            decimals = 18,
            icon = "token_usdt",
        ),
        chain = bnbSmartChain,
    ),
    StackedSample(
        label = "Binance Pegged ETH",
        token = TokenEntry(56L, "0x2170ed0880ac9a755fd29b2688956bd959f933f8", "ETH", "Binance Pegged ETH", 18, null),
        chain = bnbSmartChain,
    ),
    StackedSample(
        label = "AAVE.e (Avalanche)",
        token = TokenEntry(
            chainId = 43114L,
            contract = "0x63a72806098bd3d9520cc43356dd78afe5d386d9",
            symbol = "AAVE.e",
            name = "Aave (Avalanche Bridge)",
            decimals = 18,
            icon = "token_aave",
        ),
        chain = avalancheChain,
    ),
    StackedSample(
        label = "WETH.e (Avalanche)",
        token = TokenEntry(
            chainId = 43114L,
            contract = "0x49d5c2bdffac6ce2bfdb6640f4f80f226bc10bab",
            symbol = "WETH.e",
            name = "Wrapped Ether (Avalanche Bridge)",
            decimals = 18,
            icon = null,
        ),
        chain = avalancheChain,
    ),
    StackedSample(
        label = "USDC (Ethereum, no badge)",
        token = TokenEntry(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6, "token_usdc"),
        chain = NetworkEntry(
            chainId = 1L,
            name = "Ethereum",
            nativeSymbol = "ETH",
            feeModel = FeeModel.L1,
            explorerName = "Etherscan",
            testnet = false,
            icon = "chain_ethereum",
        ),
    ),
)

private fun LazyGridScope.section(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IconCell(resource: Int, label: String, backdrop: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BackdropLogo(
            painter = painterResource(resource),
            size = 48.dp,
            backdrop = backdrop,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StackedCell(sample: StackedSample) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TokenIcon(token = sample.token, size = 48.dp, chain = sample.chain)
        Spacer(Modifier.height(6.dp))
        Text(
            text = sample.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The two adaptive layers, drawn at their full 108 dp and cropped to the 72 dp circle a launcher shows. */
@Composable
private fun LauncherCell() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.requiredSize(108.dp)) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "launcher (adaptive)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
