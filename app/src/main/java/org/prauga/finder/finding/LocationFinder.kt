// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** This phone's own location from the platform providers, without Google services. */
class LocationFinder(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** A recent location, or null without permission or a fix. */
    @SuppressLint("MissingPermission")
    suspend fun locate(): Location? {
        if (!hasPermission()) return null
        val providers = providers()
        lastKnown(providers)
            ?.takeIf { System.currentTimeMillis() - it.time < RECENT_MILLIS }
            ?.let {
                return it
            }
        for (provider in providers) {
            withTimeoutOrNull(FIX_TIMEOUT_MILLIS) { current(provider) }
                ?.let {
                    return it
                }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(providers: List<String>): Location? =
        providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }

    private fun providers(): List<String> =
        buildList {
                add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION))
                    add(LocationManager.GPS_PROVIDER)
            }
            .filter { it in manager.allProviders && manager.isProviderEnabled(it) }

    @SuppressLint("MissingPermission")
    private suspend fun current(provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
    }

    private companion object {
        const val RECENT_MILLIS = 2 * 60_000L
        const val FIX_TIMEOUT_MILLIS = 20_000L
    }
}
