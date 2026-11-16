package com.robertobiggi.cardcollection.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives the outcome of the installation of an update ([com.robertobiggi.cardcollection.data.update.AppUpdater.install]). The system first asks for the user's confirmation: the
 * confirmation screen is opened, and also offered as a notification, because the system may refuse to open it while the app is in the background.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    /**
     * Handles the status sent by the package installer.
     * @param context Context - context.
     * @param intent Intent - status of the installation session.
     * @return Unit
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationOf(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: RuntimeException) {
                    // Not allowed from the background: the notification below is the way.
                }
                Notifications.showUpdateReady(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Notifications.cancelUpdate(context)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Notifications.cancelUpdate(context) // the user said no
            else -> Notifications.showUpdateProblem(context, context.getString(com.robertobiggi.cardcollection.R.string.update_install_failed))
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
