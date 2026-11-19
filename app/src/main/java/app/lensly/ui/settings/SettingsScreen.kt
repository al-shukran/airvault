package app.lensly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.app.HideDelay
import app.airvault.chains.bitcoin.signer.BtcFeeThresholds
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.SigningSourcePolicy
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.registry.WalletEntry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.vault.FailureDelay
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.ChoiceDialogRow
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsIsland
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.theme.LenslySpacing

/** The networks, tokens and approved contracts the catalog holds: the built-in entries plus any installed package. */
data class Catalog(
    val networks: List<NetworkEntry>,
    val tokens: List<TokenEntry>,
    val whitelist: List<WhitelistEntry> = emptyList(),
    val denylist: List<DenylistEntry> = emptyList(),
    val wallets: List<WalletEntry> = emptyList(),
    /** True when an installed package is present but failed verification, so the built-in whitelist is in force. */
    val installedUpdateUnverified: Boolean = false,
)

/** The count with its noun in the right form, such as "1 network" or "12 networks". */
private fun Int.countOf(one: String, many: String): String = "$this ${if (this == 1) one else many}"

/** The size of an icon in a catalog row's preview, and how many a preview shows. */
private val PREVIEW_ICON_SIZE: Dp = 28.dp
private const val PREVIEW_ICONS = 4

/**
 * The Settings screen of the vault: Entry, Bulk generation, the wrong password lockout, the catalog and About, one row style
 * throughout.
 *
 * [bulkCap] is null until the saved setting has been read, and its row is off until then. [onBulkCap] saves a choice from its
 * dialog at once. [onChangePassword] opens the App Password change, and [onWipe] opens the wipe. [failureDelayCap] is the saved
 * wrong password lockout, null while it is read, and [onFailureDelayCap] opens the screen that changes it. [catalog] is null
 * while it is read, and [onNetworks] and [onTokens] open the lists. [entryGesture] is the gesture the camera uses now, and
 * [onEntryGesture] opens the choice of another. [onApprovedContracts] opens the approved contracts, which [catalog] counts.
 * [proxyPolicy] is the policy in force, null while it is read, and [onProxyPolicy] opens the screen that changes it.
 * [unknownCallPolicy] is the choice in force, null while it is read, and [onUnknownCallPolicy] saves a choice from its dialog at once.
 * [hideDelay] is the dice and coin hide delay, null while it is read, and [onHideDelay] opens the screen that changes it.
 * [onThirdPartyLicences] opens the third-party licences from the About section.
 */
@Composable
fun SettingsScreen(
    bulkCap: BulkCap?,
    onBulkCap: (BulkCap) -> Unit,
    onChangePassword: () -> Unit,
    entryGesture: EntryGesture = EntryGesture.DEFAULT,
    onEntryGesture: () -> Unit = {},
    failureDelayCap: FailureCapChoice?,
    failureEraseAfter: Int = FailureDelay.DEFAULT_WIPE_AFTER,
    onFailureDelayCap: () -> Unit,
    catalog: Catalog?,
    onNetworks: () -> Unit,
    onTokens: () -> Unit,
    onApprovedContracts: () -> Unit = {},
    onAddressBook: () -> Unit = {},
    proxyPolicy: ProxyPolicy? = null,
    onProxyPolicy: () -> Unit = {},
    unknownCallPolicy: UnknownCallPolicy? = null,
    onUnknownCallPolicy: (UnknownCallPolicy) -> Unit = {},
    hideDelay: HideDelay? = null,
    onHideDelay: () -> Unit = {},
    bitcoinInputPolicy: InputVerificationPolicy? = null,
    bitcoinSigningSource: SigningSourcePolicy? = null,
    onBitcoinPolicy: () -> Unit = {},
    bitcoinFeeThresholds: BtcFeeThresholds? = null,
    onBitcoinFeeWarnings: () -> Unit = {},
    onThirdPartyLicences: () -> Unit = {},
    onUpdateCatalog: () -> Unit,
    onWipe: () -> Unit,
    onDuressPassword: () -> Unit = {},
    onRemoveDuressPassword: () -> Unit = {},
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Settings", modifier = modifier, onBack = onBack) {
        SettingsIsland("Entry") {
            SettingsRow(
                title = "Hidden Entry Gesture",
                description = entryGesture.title,
                onClick = onEntryGesture,
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Change Application Password",
                description = "Enter the current password, then the new one twice. Your wallets stay as they are.",
                onClick = onChangePassword,
                divided = false,
            )
            IslandDivider()
            // The same two rows show in the real vault and in a decoy, so the decoy's Settings look identical.
            SettingsRow(
                title = "Duress Password",
                description = "A second password. Enter the Application Password, then the new one twice.",
                onClick = onDuressPassword,
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Remove Duress Password",
                description = "Enter the Application Password. The second password stops opening anything.",
                onClick = onRemoveDuressPassword,
                divided = false,
            )
        }

        SettingsIsland("Bulk Generation") {
            ChoiceDialogRow(
                label = "Bulk Cap",
                value = bulkCap?.label,
                options = BulkCap.entries,
                selected = bulkCap,
                optionLabel = { it.label },
                onSelect = onBulkCap,
                description = "The most accounts one bulk run can create. A run above it is refused.",
                dialogTitle = "Bulk Cap",
                divided = false,
            )
        }

        SettingsIsland("Dice and Coin Entry") {
            SettingsRow(
                title = "Hide Delay",
                description = hideDelay?.let { "From ${it.minMs} to ${it.maxMs} ms after a tap." } ?: "Reading the saved delay…",
                onClick = onHideDelay,
                loading = hideDelay == null,
                divided = false,
            )
        }

        SettingsIsland("Bitcoin") {
            val policies = bitcoinInputPolicy?.let { input -> bitcoinSigningSource?.let { input to it } }
            SettingsRow(
                title = "Bitcoin Policy",
                description = policies?.let { (input, source) ->
                    "Inputs: ${inputPolicyLabel(input)}. Signing: ${signingSourceLabel(source)}."
                } ?: "Reading the saved policy…",
                onClick = onBitcoinPolicy,
                loading = policies == null,
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Fee Warnings",
                description = bitcoinFeeThresholds?.let {
                    "Warn above ${it.highFeeRateSatPerVb} sat/vB, or when the fee is over ${it.feeSharePercent}% of the amount sent."
                } ?: "Reading the saved limits…",
                onClick = onBitcoinFeeWarnings,
                loading = bitcoinFeeThresholds == null,
                divided = false,
            )
        }

        SettingsIsland("Lockout") {
            SettingsRow(
                title = "Wrong Password Lockout",
                description = failureDelayCap?.let {
                    "${it.summary}. Erased after $failureEraseAfter wrong tries in a row."
                } ?: "Reading the saved lockout…",
                onClick = onFailureDelayCap,
                loading = failureDelayCap == null,
                divided = false,
            )
        }

        SettingsIsland("Catalog") {
            SettingsRow(
                title = "Networks",
                description = catalog?.let { it.networks.size.countOf("network", "networks") } ?: "Reading the catalog…",
                onClick = onNetworks,
                loading = catalog == null,
                preview = { IconStack(items = catalog?.networks.orEmpty()) { ChainIcon(network = it, size = PREVIEW_ICON_SIZE) } },
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Tokens",
                description = catalog?.let { it.tokens.size.countOf("token", "tokens") } ?: "Reading the catalog…",
                onClick = onTokens,
                loading = catalog == null,
                preview = { IconStack(items = catalog?.tokens.orEmpty()) { TokenIcon(token = it, size = PREVIEW_ICON_SIZE) } },
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Denylist",
                description = catalog?.let { it.denylist.size.countOf("entry", "entries") } ?: "Reading the catalog…",
                loading = catalog == null,
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Wallet Compatibility",
                description = catalog?.let { it.wallets.size.countOf("wallet", "wallets") } ?: "Reading the catalog…",
                loading = catalog == null,
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Update Catalog",
                description = "Install a signed package from removable storage. It adds networks and tokens, and sets the " +
                    "approved contracts.",
                onClick = onUpdateCatalog,
                divided = false,
            )
            if (catalog?.installedUpdateUnverified == true) {
                IslandDivider()
                SettingsRow(
                    title = "Installed Update",
                    description = "Installed update could not be verified; using the built-in catalog",
                    divided = false,
                )
            }
        }

        SettingsIsland("Address Book") {
            SettingsRow(
                title = "Address Book",
                description = "Saved addresses, each with a name and a label",
                onClick = onAddressBook,
                divided = false,
            )
        }

        SettingsIsland("Whitelist") {
            SettingsRow(
                title = "Approved Contracts",
                description = catalog?.let { it.whitelist.size.countOf("approved call", "approved calls") }
                    ?: "Reading the catalog…",
                onClick = onApprovedContracts,
                loading = catalog == null,
                divided = false,
            )
            IslandDivider()
            SettingsRow(
                title = "Proxy Policy",
                description = proxyPolicy?.let { policy ->
                    if (policy == ProxyPolicy.DEFAULT) "${policy.title} (default)" else policy.title
                } ?: "Reading the saved policy…",
                onClick = onProxyPolicy,
                loading = proxyPolicy == null,
                divided = false,
            )
            IslandDivider()
            ChoiceDialogRow(
                label = "Unknown Contract Calls",
                value = unknownCallPolicy?.let { policy ->
                    if (policy == UnknownCallPolicy.DEFAULT) "${policy.label} (default)" else policy.label
                },
                options = UnknownCallPolicy.entries,
                selected = unknownCallPolicy,
                optionLabel = { it.label },
                onSelect = onUnknownCallPolicy,
                description = "A call or program Lensly cannot read. Warn signs it after an error-level warning. " +
                    "Refuse never signs it.",
                dialogTitle = "Unknown Contract Calls",
                divided = false,
            )
        }

        SettingsIsland("Wipe") {
            SettingsRow(
                title = "Wipe the Vault",
                description = "Erase every wallet and the Application Password from this device. A new Application Password is set up next.",
                onClick = onWipe,
                divided = false,
            )
        }

        SettingsIsland("About") {
            SettingsRow(title = "Version", description = "$versionName ($versionCode)", divided = false)
            IslandDivider()
            SettingsRow(title = "License", description = "Apache License 2.0. The text is in the LICENSE file.", divided = false)
            IslandDivider()
            SettingsRow(
                title = "Third-Party Licences",
                description = "Every library and font the application ships, with the licence it is used under.",
                onClick = onThirdPartyLicences,
                divided = false,
            )
        }
        Spacer(Modifier.height(LenslySpacing.xl))
    }
}

/**
 * The first few [items] as icons that overlap, the first at the left. Each icon sits on a black disc, so the edge of the one in
 * front shows against the one behind it.
 */
@Composable
private fun <T> IconStack(items: List<T>, icon: @Composable (T) -> Unit) {
    val shown = items.take(PREVIEW_ICONS)
    val step = PREVIEW_ICON_SIZE * 0.6f
    Box(modifier = Modifier.width(PREVIEW_ICON_SIZE + step * (shown.size - 1).coerceAtLeast(0).toFloat())) {
        shown.forEachIndexed { index, item ->
            Box(
                modifier = Modifier
                    .offset(x = step * index.toFloat())
                    .size(PREVIEW_ICON_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                icon(item)
            }
        }
    }
}
