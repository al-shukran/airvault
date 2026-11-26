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
 * arrival cannot nag. It shows only the cover's words, so the arrival stays behind the hidden-area gesture even in the notification shade.
 */
class ArrivalNotifications(private val context: Context) : ArrivalNotice {
    override fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Posts under a constant tag, so the notice names no transaction. [id] is not shown to any listener. A later arrival replaces the notice still
     * in the shade, as the same transaction always did.
     */
    @SuppressLint("MissingPermission") // canPost checks the permission before this is called.
    override fun post(id: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, COVER_TITLE, NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(COVER_TITLE)
            .setContentText(COVER_TEXT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTICE_TAG, 0, notification)
    }

    private companion object {
        // The channel keeps its ID, so an install that already has it is renamed rather than left with an old channel.
        const val CHANNEL_ID = "arrivals"

        // A constant tag: StatusBarNotification.getTag() is readable by any notification listener, so it must not carry the transaction id.
        const val NOTICE_TAG = "arrival"
        const val COVER_TITLE = "Camera"
        const val COVER_TEXT = "Tap to open"
    }
}
