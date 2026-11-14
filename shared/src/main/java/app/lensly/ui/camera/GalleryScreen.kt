package app.lensly.ui.camera

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.ErrorState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long the deleted notice stays, in milliseconds. */
private const val DELETED_NOTICE_MS = 2500L

/** Height of the fade behind the viewer's controls, so they stay readable over a bright photo. */
private val VIEWER_SCRIM_HEIGHT = 112.dp

/** Grid of the photos and videos in the app's private folder. Tap one to view it full screen. */
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { PhotoStore.of(context) }
    var photos by remember { mutableStateOf(store.list()) }
    var openedName by rememberSaveable { mutableStateOf<String?>(null) }
    var deletions by remember { mutableIntStateOf(0) }
    var showDeleted by remember { mutableStateOf(false) }
    var deletedNotice by remember { mutableStateOf("") }
    val opened = photos.firstOrNull { it.name == openedName }

    // Each deletion restarts the notice, so a second delete shows it for the full time.
    LaunchedEffect(deletions) {
        if (deletions > 0) {
            showDeleted = true
            delay(DELETED_NOTICE_MS)
            showDeleted = false
        }
    }

    LenslyScreen(
        title = "Gallery",
        modifier = modifier,
        onBack = onBack,
        scrollable = false,
    ) {
        if (showDeleted) {
            InlineBanner(
                message = deletedNotice,
                tone = BannerTone.Info,
                modifier = Modifier.padding(bottom = LenslySpacing.sm),
            )
        }
        if (photos.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = CameraIcons.Photos,
                    title = "No photos or videos yet",
                    body = "Photos and videos you take appear here. They stay on this phone.",
                    actionLabel = "Back to camera",
                    onAction = onBack,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = LenslySpacing.md),
                verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
            ) {
                items(photos, key = { it.name }) { file ->
                    MediaTile(file = file, onClick = { openedName = file.name })
                }
            }
        }
    }

    if (opened != null) {
        MediaViewer(
            file = opened,
            onClose = { openedName = null },
            onDelete = {
                deletedNotice = if (opened.isVideo) "Video deleted." else "Photo deleted."
                store.delete(opened)
                photos = store.list()
                openedName = null
                deletions++
            },
        )
    }
}

/** One grid cell. A video shows its length in the corner. It dims while pressed and plays the key haptic. */
@Composable
private fun MediaTile(file: File, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .pressFeedback(interaction, enabled = true)
            .clip(RoundedCornerShape(LenslyRadius.xs))
            .tapHaptic(haptics)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = if (file.isVideo) "Video" else "Photo" },
    ) {
        PhotoImage(
            file = file,
            maxSidePx = 320,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (file.isVideo) {
            DurationBadge(file = file, modifier = Modifier.align(Alignment.BottomEnd).padding(LenslySpacing.xxs))
        }
    }
}

/** The length of a video in a small dark tag. It appears once the length has been read from the file. */
@Composable
private fun DurationBadge(file: File, modifier: Modifier = Modifier) {
    val durationMs by produceState<Long?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { videoDurationMs(file) }
    }
    durationMs?.let { ms ->
        Text(
            text = formatDuration(ms),
            style = MaterialTheme.typography.labelSmall,
            color = LenslyOnSurface,
            modifier = modifier
                .clip(LenslyPillShape)
                .background(LenslyBlack.copy(alpha = 0.6f))
                .padding(horizontal = LenslySpacing.xs, vertical = LenslySpacing.xxs),
        )
    }
}

@Composable
private fun MediaViewer(
    file: File,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var confirmingDelete by remember { mutableStateOf(false) }
    val kind = if (file.isVideo) "video" else "photo"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LenslyBlack),
    ) {
        if (file.isVideo) {
            VideoPlayer(file = file, modifier = Modifier.fillMaxSize())
        } else {
            PhotoImage(
                file = file,
                maxSidePx = 1600,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                contentDescription = "Photo",
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(VIEWER_SCRIM_HEIGHT)
                .background(
                    Brush.verticalGradient(
                        listOf(LenslyBlack.copy(alpha = 0.7f), Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = LenslySpacing.xxs, vertical = LenslySpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close",
                    tint = LenslyOnSurface,
                )
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete $kind",
                    tint = LenslyOnSurface,
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = LenslySurface,
            title = {
                Text(
                    text = "DELETE ${kind.uppercase()}?",
                    style = MaterialTheme.typography.titleMedium,
                    color = LenslyOnSurface,
                )
            },
            text = {
                Text(
                    text = "The $kind is removed from this phone. It cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text(
                        text = "DELETE",
                        style = MaterialTheme.typography.labelLarge,
                        color = LenslyError,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(
                        text = "CANCEL",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** Plays a saved video with the player's own controls. It starts when opened and stops when closed. */
@Composable
private fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    // A file the player cannot decode shows the app's own error state instead of the system's "can't play" dialog.
    var unplayable by remember(file) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (unplayable) {
            ErrorState(
                icon = CameraIcons.Photos,
                title = "Video can't be played",
                body = "The file may be damaged, or this phone may not read its format.",
            )
        } else {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        setOnErrorListener { _, _, _ ->
                            unplayable = true
                            // Handled here, so the player does not show its own error dialog.
                            true
                        }
                        setVideoURI(Uri.fromFile(file))
                        start()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopPlayback() },
            )
        }
    }
}
