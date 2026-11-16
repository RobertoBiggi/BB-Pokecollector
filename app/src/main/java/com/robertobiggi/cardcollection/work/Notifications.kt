package com.robertobiggi.cardcollection.work

import android.Manifest
import android.app.Notification
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
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.price.AlertEvent
import com.robertobiggi.cardcollection.core.price.PricePoint
import com.robertobiggi.cardcollection.data.repo.PriceAlertNotice
import com.robertobiggi.cardcollection.ui.MainActivity
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.labelRes
import java.util.Locale
import kotlin.math.abs

/** Channels and notifications: progress of background jobs (silent) and price alerts. */
object Notifications {
    const val CHANNEL_ID = "updates"

    /** Price alerts channel: separate, so one can be muted without the other. */
    const val ALERTS_CHANNEL_ID = "price_alerts"

    /** App updates channel: the update waiting for the user's confirmation, or failed (the offer itself is in the app). */
    const val UPDATE_CHANNEL_ID = "app_update"

    private const val UPDATE_NOTIFICATION_ID = 0x75D47E

    /**
     * Creates the notification channel (required from Android 8).
     * @param context Context - application context.
     * @return Unit
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_description) }
        val alerts = NotificationChannel(
            ALERTS_CHANNEL_ID,
            context.getString(R.string.notification_alerts_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_alerts_channel_description) }
        val update = NotificationChannel(
            UPDATE_CHANNEL_ID,
            context.getString(R.string.notification_update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_update_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, alerts, update))
    }

    /**
     * Shows a price alert; tapping it opens the card detail. Without the notification permission it does nothing.
     * @param context Context - application context.
     * @param notice PriceAlertNotice - alert to show.
     * @return Boolean - true if the notification was shown.
     */
    fun showPriceAlert(context: Context, notice: PriceAlertNotice): Boolean {
        if (!canNotify(context)) return false
        val variant = notice.variantType?.let { context.getString(it.labelRes()) }
        val title = listOfNotNull(notice.cardName, variant).joinToString(" · ")
        val text = when (val event = notice.event) {
            is AlertEvent.Change -> context.getString(
                if (event.percent > 0) R.string.alert_price_up else R.string.alert_price_down,
                event.point.source, String.format(Locale.getDefault(), "%.1f", abs(event.percent)), money(event.point), money(event.point.copy(value = event.previous)),
            )
            is AlertEvent.TargetReached -> context.getString(
                R.string.alert_price_target, event.point.source, money(event.point), Format.eur(event.target),
            )
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CARD_ID, notice.cardId)
            notice.collectionId?.let { putExtra(MainActivity.EXTRA_COLLECTION_ID, it) }
        }
        val id = "${notice.cardId}|${notice.variantType}|${notice.event::class.simpleName}|${(notice.event as? AlertEvent.Change)?.point?.key}".hashCode()
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            return false
        }
        return true
    }

    /**
     * Shows that the downloaded update waits for the user's confirmation (the system asks it); tapping it opens the system's installer.
     * @param context Context - application context.
     * @param confirm Intent - the installer's confirmation screen.
     * @return Boolean - true if the notification was shown.
     */
    fun showUpdateReady(context: Context, confirm: Intent): Boolean {
        if (!canNotify(context)) return false
        val pending = PendingIntent.getActivity(context, UPDATE_NOTIFICATION_ID + 1, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return notifyUpdate(context, context.getString(R.string.update_ready_title), context.getString(R.string.update_ready_text), pending)
    }

    /**
     * Shows that the update failed or cannot start.
     * @param context Context - application context.
     * @param message String - already translated reason.
     * @return Boolean - true if the notification was shown.
     */
    fun showUpdateProblem(context: Context, message: String): Boolean {
        if (!canNotify(context)) return false
        val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = PendingIntent.getActivity(context, UPDATE_NOTIFICATION_ID + 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return notifyUpdate(context, context.getString(R.string.update_problem_title), message, pending)
    }

    /** Removes the update notification (installation started or done). */
    fun cancelUpdate(context: Context) {
        NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID)
    }

    private fun notifyUpdate(context: Context, title: String, text: String, pending: PendingIntent): Boolean {
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Checks that the app can show notifications (permission on Android 13+ and notifications not disabled).
     * @param context Context - context.
     * @return Boolean - true if they can be shown.
     */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Formats the price of an alert in its currency.
     * @param point PricePoint - price.
     * @return String - formatted amount.
     */
    private fun money(point: PricePoint): String = if (point.currency == PricePoint.USD) Format.usd(point.value) else Format.eur(point.value)

    /**
     * Builds the notification of a running job, with indeterminate progress.
     * @param context Context - context.
     * @param title String - already translated title.
     * @param text String? - what it is doing, already translated.
     * @param done Int - items finished; with a positive [total] the bar shows progress, otherwise it is indeterminate.
     * @param total Int - items to do.
     * @return Notification - notification ready.
     */
    fun progress(context: Context, title: String, text: String? = null, done: Int = 0, total: Int = 0): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done.coerceIn(0, total.coerceAtLeast(0)), total <= 0)
            .build()
}
