// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.reports

import android.content.Context
import android.os.BatteryManager
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.finding.LocationFinder
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.nostr.RelayClient
import org.prauga.finder.report.ReportedLocation
import org.prauga.finder.report.SelfReport

/** Posts this phone's own location to the chosen boards every 30 minutes while enabled. */
class Reporter(
    private val context: Context,
    private val settings: ReportSettings,
    private val identity: IdentityRepository,
    private val locations: LocationFinder,
    private val http: OkHttpClient,
    private val log: DiagnosticsLog,
) {
    /** Runs until cancelled. */
    suspend fun run() {
        while (true) {
            val reported = !settings.enabled.value || settings.boards.value.isEmpty() || reportOnce()
            delay(if (reported) SelfReport.INTERVAL_SECONDS * 1_000 else RETRY_MILLIS)
        }
    }

    /** Returns false when there was no location to report, so it is worth trying again soon. */
    suspend fun reportOnce(): Boolean {
        val location = locations.locate()
        if (location == null) {
            log.add(DiagnosticEvent.Kind.ERROR, "Report skipped: no location")
            return false
        }
        val now = System.currentTimeMillis() / 1_000
        val secret = identity.beaconSecret()
        val event =
            SelfReport.event(
                secret,
                now,
                ReportedLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMetres = location.accuracy.toInt(),
                    takenAtSeconds = location.time / 1_000,
                    batteryPercent = battery(),
                ),
            )
        secret.fill(0)
        var stored = 0
        for (board in settings.boards.value) {
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    val client = RelayClient(OkHttpRelaySocket.connect(http, board))
                    try {
                        if (client.publish(event)) stored++
                    } finally {
                        client.close()
                    }
                }
            } catch (e: IOException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Report to a board failed: ${e.message}")
            } catch (e: TimeoutCancellationException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Report to a board timed out")
            }
        }
        log.add(
            DiagnosticEvent.Kind.ADVERTISER,
            "Reported location to $stored of ${settings.boards.value.size} boards",
        )
        return true
    }

    private fun battery(): Int? =
        context
            .getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val RETRY_MILLIS = 2 * 60_000L
    }
}
