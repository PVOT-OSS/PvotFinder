// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.prauga.finder.FinderApplication
import org.prauga.finder.MainActivity
import org.prauga.finder.R

/** Plays the alarm sound for a paired phone, always with a notification that can stop it. */
class Ringer(private val context: Context, private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var stopJob: Job? = null

    /** Returns false without a sound when no notification could be shown to stop it. */
    fun ring(peerName: String, seconds: Int): Boolean {
        stop()
        if (!canNotify()) return false
        showNotification(peerName)
        val uri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        stopJob =
            scope.launch {
                delay(seconds * 1_000L)
                stop()
            }
        return true
    }

    fun canNotify(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun stop() {
        stopJob?.cancel()
        stopJob = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun showNotification(peerName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ring_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply { setSound(null, null) }
        )
        if (
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val stop =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, StopRingReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wave_sine)
                .setContentTitle(context.getString(R.string.ring_title))
                .setContentText(context.getString(R.string.ring_text, peerName))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(open)
                .setDeleteIntent(stop)
                .addAction(0, context.getString(R.string.ring_stop), stop)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "ring"
        const val NOTIFICATION_ID = 1
    }
}

class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as FinderApplication).container.ringer.stop()
    }
}
