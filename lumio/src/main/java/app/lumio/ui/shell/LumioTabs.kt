package app.lumio.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LenslyIcons

/** The tabs of Lumio's bar, in the order the bar shows them. Each is a root screen, so a tab opens in place of the one before it. */
enum class LumioTab(val label: String, val tag: String) {
    WALLETS("Wallets", "tab-wallets"),
    SETTINGS("Settings", "tab-settings"),
}

/** The height of the bar above the navigation bar. */
private val BAR_HEIGHT = 64.dp

/** The height of the line that marks the tab in use. */
private val MARK_HEIGHT = 2.dp

/** The gap between the mark and the icon of a tab. */
private val TAB_GAP = 6.dp

/**
 * A root screen with the tab bar at its foot: [content] fills the space above the bar, and the bar sits above the navigation bar. The content does
 * not take the navigation bar's inset, because the bar takes it, so the content meets the bar without a gap.
 */
@Composable
fun LumioTabFrame(
    selected: LumioTab,
    onSelect: (LumioTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
            content()
        }
        LumioTabBar(selected = selected, onSelect = onSelect)
    }
}

/**
 * The bar of two tabs, Wallets and Settings. The tab in use is in the bright colour and marked by a line above it, and the other is muted. The bar is
 * flat, so a tab is never drawn as a button.
 */
@Composable
fun LumioTabBar(selected: LumioTab, onSelect: (LumioTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BAR_HEIGHT),
    ) {
        LumioTab.entries.forEach { tab ->
            TabItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabItem(tab: LumioTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color: Color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag(tab.tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MARK_HEIGHT)
                .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent),
        )
        Spacer(Modifier.height(TAB_GAP))
        Icon(imageVector = iconOf(tab), contentDescription = null, tint = color)
        Text(text = tab.label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** The icon of a tab. */
private fun iconOf(tab: LumioTab): ImageVector = when (tab) {
    LumioTab.WALLETS -> LenslyIcons.Wallets
    LumioTab.SETTINGS -> Icons.Filled.Settings
}
