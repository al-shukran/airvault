package app.lumio.send

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lumio.MainActivity
import app.lumio.R

/**
 * The arrival notice, on its own channel. It posts only when the owner has allowed notifications: this class never asks for the permission, so an
 * arrival cannot nag. Before unlock the lock screen shows only the cover's words, so the arrival stays behind the gesture.
 */
class ArrivalNotifications(private val context: Context) : ArrivalNotice {
    override fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission") // canPost checks the permission before this is called.
    override fun post(id: String, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, COVER_TITLE, NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cover = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(COVER_TITLE)
            .setContentText(COVER_TEXT)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(cover)
            .build()
        manager.notify(id, 0, notification)
    }

    private companion object {
        // The channel keeps its ID, so an install that already has it is renamed rather than left with an old channel.
        const val CHANNEL_ID = "arrivals"
        const val COVER_TITLE = "Camera"
        const val COVER_TEXT = "Tap to open"
    }
}
