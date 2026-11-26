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

    // Transaction id -> notification id. In memory only, and never given to the system as a tag, which any notification listener can read.
    private val notificationIds = HashMap<String, Int>()

    // The next id to hand out. Seeded on first use after the ids the shade still shows, so a restarted process does not reuse one.
    private var nextNotificationId: Int? = null

    /**
     * Each distinct arrival gets its own notification id, so arrivals stack; a repeat of the same [id] updates its notification. No tag is set,
     * so the notice names no transaction.
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
        manager.notify(notificationId(id), notification)
    }

    private fun notificationId(id: String): Int = synchronized(notificationIds) {
        notificationIds.getOrPut(id) {
            val next = nextNotificationId ?: (shownNotificationIds() + 1)
            nextNotificationId = next + 1
            next
        }
    }

    private fun shownNotificationIds(): Int =
        context.getSystemService(NotificationManager::class.java).activeNotifications.maxOfOrNull { it.id } ?: -1

    private companion object {
        // The channel keeps its ID, so an install that already has it is renamed rather than left with an old channel.
        const val CHANNEL_ID = "arrivals"
        const val COVER_TITLE = "Camera"
        const val COVER_TEXT = "Tap to open"
    }
}
