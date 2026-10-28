package com.robertobiggi.cardcollection

import android.app.Application
import com.robertobiggi.cardcollection.work.Notifications

/** Applicazione: crea il contenitore delle dipendenze e il canale delle notifiche. */
class CardCollectionApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Inizializza le dipendenze all'avvio del processo.
     * @return Unit
     */
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannel(this)
    }
}
