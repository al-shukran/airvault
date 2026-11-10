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
import app.lensly.ui.components.BackdropLogo
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
        section("APP AND FALLBACK")
        item { IconCell(resource = R.drawable.ic_unknown_token, label = "ic_unknown_token") }
        item { IconCell(resource = R.drawable.logo_lensly, label = "logo_lensly") }
        item { LauncherCell() }
    }
}

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
