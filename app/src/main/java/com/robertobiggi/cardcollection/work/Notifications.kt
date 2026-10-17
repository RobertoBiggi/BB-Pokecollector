package com.robertobiggi.cardcollection.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.robertobiggi.cardcollection.R

/** Canale e notifiche di avanzamento dei lavori in background. */
object Notifications {
    const val CHANNEL_ID = "updates"

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
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Costruisce la notifica di avanzamento.
     * @param context Context - contesto.
     * @param title String - titolo.
     * @param text String - testo di dettaglio.
     * @param done Int - elementi completati.
     * @param total Int - elementi totali (0 = avanzamento indeterminato).
     * @return Notification - notifica pronta.
     */
    fun progress(context: Context, title: String, text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done.coerceAtMost(total), total == 0)
            .build()
}
