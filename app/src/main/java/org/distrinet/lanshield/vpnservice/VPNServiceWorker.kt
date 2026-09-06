package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.distrinet.lanshield.LocalNetworkPermission
import org.distrinet.lanshield.R

class VPNServiceWorker(private val appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    private fun hasVPNConsent(): Boolean {
        return VpnService.prepare(appContext) == null
    }

    override fun doWork(): Result {
        val context = applicationContext
        // Check the service's start preconditions here, before startForegroundService(): that call
        // is a promise to go foreground within seconds, and from BOOT_COMPLETED the only allowed
        // type (systemExempted) itself depends on VPN consent. So when a precondition is missing,
        // tell the user from here and don't start the service at all.
        val problem = when {
            !LocalNetworkPermission.isGranted(context) ->
                R.string.local_network_permission_missing_title to R.string.local_network_permission_missing_text
            !hasVPNConsent() ->
                R.string.lanshield_start_failed_title to R.string.vpn_consent_missing_text
            else -> null
        }
        if (problem != null) {
            val notifications = LANShieldNotificationManager(context)
            notifications.createNotificationChannels()
            notifications.postServiceErrorNotification(
                context.getString(problem.first),
                context.getString(problem.second)
            )
            return Result.failure()
        }
        context.startForegroundService(Intent(context, VPNService::class.java))
        return Result.success()
    }

    companion object {
        /**
         * Starts the VPNService, does not require the caller to run in the foreground.
         */
        fun enqueueStartVpnService(context: Context) {
            val workRequest = OneTimeWorkRequest.Builder(VPNServiceWorker::class.java).build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}