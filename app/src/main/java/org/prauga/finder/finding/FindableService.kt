// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.prauga.finder.FinderApplication
import org.prauga.finder.MainActivity
import org.prauga.finder.R

/**
 * Keeps this phone advertising and answering its paired phones while no screen of the app is
 * shown. Runs only while the user has "Let my phones find this one" on.
 */
class FindableService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reporting: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as FinderApplication).container
        val reports = container.reportSettings.enabled.value && container.locations.hasPermission()
        val types =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (reports) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        container.discovery.setBackgroundAdvertising(true)
        reporting?.cancel()
        reporting = if (reports) scope.launch { container.reporter.run() } else null
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        (application as FinderApplication).container.discovery.setBackgroundAdvertising(false)
        super.onDestroy()
    }

    private fun notification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wave_sine)
            .setContentTitle(getString(R.string.findable_title))
            .setContentText(getString(R.string.findable_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
            .also {
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            getString(R.string.findable_channel),
                            NotificationManager.IMPORTANCE_LOW,
                        )
                    )
            }

    companion object {
        private const val CHANNEL_ID = "findable"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FindableService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FindableService::class.java))
        }
    }
}
