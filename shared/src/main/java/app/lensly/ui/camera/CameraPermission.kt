package app.lensly.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.labelCase

fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}

/** The name of the app that hosts this screen, from its launcher label: "Lensly" or "Lumio". */
fun Context.hostAppName(): String = applicationInfo.loadLabel(packageManager).toString()

/**
 * Request screen shown until camera access is granted. Before the first ask it says why the camera is needed. Once the
 * system prompt has been shown ([canOpenSettings]), it says access is off and offers the route to system settings, which
 * is the one that still works after a permanent refusal.
 */
@Composable
fun CameraPermissionRationale(
    canOpenSettings: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val appName = remember(context) { context.hostAppName() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = LenslySpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = CameraIcons.Camera,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = labelCase(if (canOpenSettings) "Camera Is Off" else "Camera Access"),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = if (canOpenSettings) {
                "Camera access is off for $appName. Turn it on in Settings, or allow it again here."
            } else {
                "$appName needs the camera to take photos. Photos stay on this phone."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.xl))
        if (canOpenSettings) {
            PillButton(text = "Open Settings", onClick = onOpenSettings, accent = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(LenslySpacing.sm))
            PillButton(text = "Allow Camera", onClick = onAllow, modifier = Modifier.fillMaxWidth())
        } else {
            PillButton(text = "Allow Camera", onClick = onAllow, accent = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
