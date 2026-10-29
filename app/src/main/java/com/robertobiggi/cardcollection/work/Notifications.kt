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

/** Canali e notifiche: avanzamento dei lavori in background (silenziose) e avvisi di prezzo. */
object Notifications {
    const val CHANNEL_ID = "updates"

    /** Canale degli avvisi di prezzo: a parte, così si può silenziare uno senza l'altro. */
    const val ALERTS_CHANNEL_ID = "price_alerts"

    /**
     * Crea il canale delle notifiche (necessario da Android 8).
     * @param context Context - contesto dell'applicazione.
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
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, alerts))
    }

    /**
     * Mostra un avviso di prezzo; toccandolo si apre il dettaglio della carta. Senza il permesso delle notifiche non fa nulla.
     * @param context Context - contesto dell'applicazione.
     * @param notice PriceAlertNotice - avviso da mostrare.
     * @return Boolean - true se la notifica è stata mostrata.
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
     * Controlla che l'app possa mostrare notifiche (permesso su Android 13+ e notifiche non disattivate).
     * @param context Context - contesto.
     * @return Boolean - true se si possono mostrare.
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
     * Formatta il prezzo di un avviso nella sua valuta.
     * @param point PricePoint - prezzo.
     * @return String - importo formattato.
     */
    private fun money(point: PricePoint): String = if (point.currency == PricePoint.USD) Format.usd(point.value) else Format.eur(point.value)

    /**
     * Costruisce la notifica di un lavoro in corso, con avanzamento indeterminato.
     * @param context Context - contesto.
     * @param title String - titolo già tradotto.
     * @param text String? - cosa sta facendo, già tradotto.
     * @param done Int - elementi finiti; con [total] positivo la barra mostra l'avanzamento, altrimenti è indeterminata.
     * @param total Int - elementi da fare.
     * @return Notification - notifica pronta.
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
