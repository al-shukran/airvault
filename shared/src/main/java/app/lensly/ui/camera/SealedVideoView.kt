package app.lensly.ui.camera

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.MediaController
import kotlin.math.roundToInt

/**
 * Plays one sealed video from [source], with the system's media controls. The plain bytes are decrypted as the player reads them and are
 * never written to storage. [onUnplayable] runs on the main thread when the player cannot open or play the video. The caller closes
 * [source], after [release].
 */
internal class SealedVideoView(
    context: Context,
    private val source: MediaDataSource,
    private val onUnplayable: () -> Unit,
) : FrameLayout(context) {
    private val surface = SurfaceView(context)
    private val player = MediaPlayer()
    private val controls = MediaController(context)
    private var prepared = false
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        addView(surface)
        surface.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    player.setDisplay(holder)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    player.setDisplay(null)
                }
            },
        )
        player.setOnVideoSizeChangedListener { _, width, height ->
            videoWidth = width
            videoHeight = height
            requestLayout()
        }
        player.setOnPreparedListener { mediaPlayer ->
            prepared = true
            mediaPlayer.start()
        }
        player.setOnErrorListener { _, _, _ ->
            onUnplayable()
            // Handled here, so the player does not show a dialog of its own.
            true
        }
        controls.setMediaPlayer(Playback())
        controls.setAnchorView(this)
        setOnClickListener { if (controls.isShowing) controls.hide() else controls.show() }
        try {
            player.setDataSource(source)
            player.prepareAsync()
        } catch (e: RuntimeException) {
            onUnplayable()
        } catch (e: java.io.IOException) {
            onUnplayable()
        }
    }

    /** Stops and frees the player. */
    fun release() {
        controls.hide()
        player.release()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val (fitWidth, fitHeight) = fit(width, height)
        surface.measure(
            MeasureSpec.makeMeasureSpec(fitWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(fitHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - surface.measuredWidth) / 2
        val y = (height - surface.measuredHeight) / 2
        surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
    }

    /** The largest size that keeps the video's shape within [maxWidth] by [maxHeight]. */
    private fun fit(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return maxWidth to maxHeight
        val scale = minOf(maxWidth.toFloat() / videoWidth, maxHeight.toFloat() / videoHeight)
        return (videoWidth * scale).roundToInt() to (videoHeight * scale).roundToInt()
    }

    /** The player as the system's media controls drive it. Before the video is prepared, the controls read it as idle. */
    private inner class Playback : MediaController.MediaPlayerControl {
        override fun start() {
            if (prepared) player.start()
        }

        override fun pause() {
            if (prepared) player.pause()
        }

        override fun getDuration(): Int = if (prepared) player.duration else 0

        override fun getCurrentPosition(): Int = if (prepared) player.currentPosition else 0

        override fun seekTo(pos: Int) {
            if (prepared) player.seekTo(pos)
        }

        override fun isPlaying(): Boolean = prepared && player.isPlaying

        override fun getBufferPercentage(): Int = 100

        override fun canPause(): Boolean = true

        override fun canSeekBackward(): Boolean = true

        override fun canSeekForward(): Boolean = true

        override fun getAudioSessionId(): Int = if (prepared) player.audioSessionId else 0
    }
}
