// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

data class PermissionState(val canScan: Boolean, val canAdvertise: Boolean) {
    val allGranted: Boolean
        get() = canScan && canAdvertise
}

class BluetoothPermissions(private val context: Context) {
    fun current(): PermissionState =
        PermissionState(
            canScan = granted(Manifest.permission.BLUETOOTH_SCAN),
            canAdvertise = granted(Manifest.permission.BLUETOOTH_ADVERTISE),
        )

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        val REQUIRED =
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}
