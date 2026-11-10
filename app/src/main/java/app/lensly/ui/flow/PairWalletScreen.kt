package app.lensly.ui.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletStore
import app.lensly.app.export.PairingExport
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.navigation.HiddenRoute
import app.lensly.ui.navigation.leaveHidden
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.qr.ShowQrScreen
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold
import app.lensly.ui.wallet.WalletWarningChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_PAIR_NAME = "Lensly"

/**
 * The online wallets that pair with Lensly. Both read the same ur:crypto-hdkey, so only the steps the owner follows
 * differ.
 */
internal enum class PairFormat(val label: String, val instruction: String) {
    RABBY(
        label = "RABBY (AIRGAP VAULT)",
        instruction = "In Rabby: Add address → Hardware wallet → AirGap Vault → scan",
    ),
    METAMASK(
        label = "METAMASK (QR-BASED)",
        instruction = "Add account or hardware wallet → Hardware wallet → QR-based → scan",
    ),
}

/**
 * The pairing frames for [wallet]: its account key with the chain code and fingerprints, named after the wallet, or
 * "Lensly" when it has no name. Public data only. The fingerprint is stored as hex, so it is read as an unsigned value.
 */
internal fun pairingParts(wallet: WalletMeta): List<String> = PairingExport.urParts(
    xpub = requireNotNull(wallet.accountXpub) { "Only a Mnemonic wallet has an account public key." },
    masterFingerprint = requireNotNull(wallet.masterFingerprintHex) { "Only a Mnemonic wallet has a fingerprint." }
        .toLong(16)
        .toInt(),
    name = wallet.name.ifBlank { DEFAULT_PAIR_NAME },
)

/**
 * PAIR WALLET for the wallet [walletId]: the owner picks the online wallet, then shows the pairing QR code. It reads only
 * public wallet data, so the open session is all it needs.
 */
@Composable
internal fun PairWalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(HiddenRoute.GATE)
    }
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    LaunchedEffect(walletId, session) {
        if (session == null) return@LaunchedEffect
        try {
            val found = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
            if (found == null) navController.popBackStack() else wallet = found
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // A closed session throws IllegalStateException, and so does a damaged vault.
            leaveHidden(navController, holder)
        }
    }
    val shown = wallet ?: return
    if (shown.kind == WalletKind.SINGLE_KEY) {
        SingleKeyPairScreen(onBack = { navController.popBackStack() })
    } else {
        PairWalletContent(wallet = shown, onDone = { navController.popBackStack() })
    }
}

/**
 * PAIR WALLET for a Single Key. Pairing hands an online wallet an account's extended public key and derivation path. A
 * single key has neither, so it cannot be paired, and no QR code is shown. Signing does not need pairing: SIGN reads a
 * request that names the wallet's address.
 */
@Composable
internal fun SingleKeyPairScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Outline,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Pair with an online wallet")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "PAIR WALLET")
        Spacer(Modifier.height(16.dp))
        WalletWarningChip(text = "Cannot be paired")
        Spacer(Modifier.height(20.dp))
        Text(
            text = "This wallet can't be paired. Pairing shares an account's extended public key and derivation path, " +
                "and a single key has neither. An online wallet that pairs by extended public key cannot watch it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "To sign with this key, open SIGN on this wallet and scan a request that names its address.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairWalletContent(wallet: WalletMeta, onDone: () -> Unit) {
    var format by remember(wallet) { mutableStateOf<PairFormat?>(null) }
    val frames by produceState<List<String>?>(initialValue = null, wallet) {
        value = withContext(Dispatchers.Default) { pairingParts(wallet) }
    }

    val chosen = format
    if (chosen == null) {
        PairChooserScreen(onPick = { format = it }, onBack = onDone)
        return
    }
    // Back from the code returns to the choice, so the owner can pick the other wallet.
    BackHandler { format = null }
    val parts = frames ?: return
    ShowQrScreen(
        title = "PAIR WALLET",
        subtitle = chosen.instruction,
        parts = parts,
        onDone = onDone,
        // The paired account is an Ethereum account, so it carries the Ethereum logo.
        subtitleLeading = { AddressIcon(size = 16.dp) },
    )
}

/** The first step of PAIR WALLET: which online wallet is being paired. The app never guesses it. */
@Composable
internal fun PairChooserScreen(
    onPick: (PairFormat) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Outline,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Pair with an online wallet")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "PAIR WALLET")
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Choose the wallet you are pairing with. Each one has its own steps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        PairFormat.entries.forEach { format ->
            WalletPill(
                text = format.label,
                onClick = { onPick(format) },
                modifier = Modifier.fillMaxWidth(),
                size = WalletPillSize.Large,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
