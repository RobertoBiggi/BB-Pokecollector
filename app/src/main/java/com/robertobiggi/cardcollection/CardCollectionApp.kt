package com.robertobiggi.cardcollection

import android.app.Application
import com.robertobiggi.cardcollection.work.Notifications

/** Application: creates the dependency container and the notification channel. */
class CardCollectionApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Initializes the dependencies when the process starts.
     * @return Unit
     */
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannel(this)
    }
}
