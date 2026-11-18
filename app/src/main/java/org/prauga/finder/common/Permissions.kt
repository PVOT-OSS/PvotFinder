// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

fun Context.hasPermissions(permissions: Array<String>): Boolean =
    permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/**
 * Asks for [permissions], or opens the app's settings once Android stops showing the prompt after
 * repeated denials. [onResult] gets whether all of them are granted.
 */
@Composable
fun rememberPermissionRequest(
    permissions: Array<String>,
    onResult: (Boolean) -> Unit = {},
): () -> Unit {
    val activity = LocalActivity.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            result ->
            asked = true
            onResult(result.values.all { it })
        }
    return {
        if (activity != null && asked && !activity.canAskAgain(permissions)) {
            activity.openAppSettings()
        } else {
            launcher.launch(permissions)
        }
    }
}

fun Activity.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    )
}

private fun Activity.canAskAgain(permissions: Array<String>): Boolean =
    permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED &&
        shouldShowRequestPermissionRationale(it) }
