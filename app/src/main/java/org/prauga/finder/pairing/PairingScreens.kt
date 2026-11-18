// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import android.Manifest
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.theme.PvotShapes
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.delay
import org.prauga.finder.R
import org.prauga.finder.SubScreen
import org.prauga.finder.bluetooth.BluetoothPermissions
import org.prauga.finder.common.hasPermissions
import org.prauga.finder.common.rememberPermissionRequest

@Composable
fun AddPhoneScreen(onShowCode: () -> Unit, onScanCode: () -> Unit, onBack: () -> Unit) {
    SubScreen(stringResource(R.string.add_phone), onBack) {
        item {
            PvotCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(PvotSpacing.L),
                    verticalArrangement = Arrangement.spacedBy(PvotSpacing.M),
                ) {
                    Text(stringResource(R.string.add_phone_intro))
                    PvotButton(
                        stringResource(R.string.show_code),
                        onShowCode,
                        Modifier.fillMaxWidth(),
                    )
                    PvotButton(
                        stringResource(R.string.scan_code),
                        onScanCode,
                        Modifier.fillMaxWidth(),
                        style = PvotButtonStyle.Tonal,
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.add_phone_note),
                modifier = Modifier.padding(horizontal = PvotSpacing.XS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ShowCodeScreen(controller: PairingController, onDone: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val allowNearby =
        rememberPermissionRequest(BluetoothPermissions.REQUIRED) { if (it) controller.show() }
    LaunchedEffect(Unit) { controller.show() }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            controller.cancel()
        }
    }
    SubScreen(stringResource(R.string.show_code), onBack) {
        item {
            when (val s = state) {
                is PairingState.Showing -> ShowingCode(s)
                PairingState.Idle,
                PairingState.Preparing -> Message(stringResource(R.string.pairing_preparing))
                else -> Progress(s, controller, onDone, retry = controller::show, allowNearby)
            }
        }
    }
}

@Composable
fun ScanCodeScreen(controller: PairingController, onDone: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var cameraAllowed by remember { mutableStateOf(context.hasPermissions(CAMERA)) }
    LifecycleResumeEffect(Unit) {
        cameraAllowed = context.hasPermissions(CAMERA)
        onPauseOrDispose {}
    }
    val askCamera = rememberPermissionRequest(CAMERA) { cameraAllowed = it }
    val allowNearby =
        rememberPermissionRequest(BluetoothPermissions.REQUIRED) { if (it) controller.reset() }
    LaunchedEffect(Unit) {
        controller.reset()
        if (!cameraAllowed) askCamera()
    }
    DisposableEffect(Unit) { onDispose { controller.cancel() } }
    SubScreen(stringResource(R.string.scan_code), onBack) {
        item {
            when (val s = state) {
                PairingState.Idle ->
                    if (cameraAllowed) {
                        Column(verticalArrangement = Arrangement.spacedBy(PvotSpacing.M)) {
                            QrScanner(
                                onCode = controller::scan,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(PvotShapes.Card),
                            )
                            Message(stringResource(R.string.scan_hint))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(PvotSpacing.M)) {
                            Message(stringResource(R.string.camera_needed))
                            PvotButton(
                                stringResource(R.string.allow_camera),
                                askCamera,
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                PairingState.Preparing -> Message(stringResource(R.string.pairing_connecting))
                else -> Progress(s, controller, onDone, retry = controller::reset, allowNearby)
            }
        }
    }
}

@Composable
private fun ShowingCode(state: PairingState.Showing) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    LaunchedEffect(state.expiresAtSeconds) {
        while (true) {
            now = System.currentTimeMillis() / 1_000
            delay(1_000)
        }
    }
    val left = (state.expiresAtSeconds - now).coerceAtLeast(0)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PvotSpacing.L),
    ) {
        QrCode(
            state.qrText,
            stringResource(R.string.qr_description),
            Modifier.fillMaxWidth(0.85f).aspectRatio(1f).clip(PvotShapes.Card),
        )
        Text(
            stringResource(R.string.code_expires, left / 60, left % 60),
            style = MaterialTheme.typography.titleMedium,
        )
        Message(stringResource(R.string.show_hint))
    }
}

@Composable
private fun Progress(
    state: PairingState,
    controller: PairingController,
    onDone: () -> Unit,
    retry: () -> Unit,
    allowNearby: () -> Unit,
) {
    PvotCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(PvotSpacing.L).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(PvotSpacing.M),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                PairingState.Connecting -> Text(stringResource(R.string.pairing_connecting))
                is PairingState.Confirming -> {
                    Text(
                        stringResource(R.string.pairing_with, state.peerName),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        state.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(
                            if (state.waitingForOther) R.string.pairing_waiting_other
                            else R.string.pairing_compare
                        ),
                        textAlign = TextAlign.Center,
                    )
                    if (!state.waitingForOther) {
                        PvotButton(
                            stringResource(R.string.codes_match),
                            controller::confirm,
                            Modifier.fillMaxWidth(),
                        )
                    }
                    PvotButton(
                        stringResource(R.string.cancel),
                        controller::cancel,
                        Modifier.fillMaxWidth(),
                        style = PvotButtonStyle.Text,
                    )
                }
                is PairingState.Paired -> {
                    Text(
                        stringResource(R.string.paired_with, state.peerName),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    PvotButton(stringResource(R.string.done), onDone, Modifier.fillMaxWidth())
                }
                is PairingState.Failed -> {
                    Text(
                        stringResource(failureText(state.reason)),
                        textAlign = TextAlign.Center,
                    )
                    if (state.reason == PairingState.Reason.PERMISSION) {
                        PvotButton(
                            stringResource(R.string.allow_nearby),
                            allowNearby,
                            Modifier.fillMaxWidth(),
                        )
                    } else {
                        PvotButton(stringResource(R.string.try_again), retry, Modifier.fillMaxWidth())
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PvotSpacing.XS),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun failureText(reason: PairingState.Reason): Int =
    when (reason) {
        PairingState.Reason.PERMISSION -> R.string.pairing_failed_permission
        PairingState.Reason.INVALID_CODE -> R.string.pairing_failed_invalid
        PairingState.Reason.EXPIRED -> R.string.pairing_failed_expired
        PairingState.Reason.BLUETOOTH -> R.string.pairing_failed_bluetooth
        PairingState.Reason.HANDSHAKE -> R.string.pairing_failed_handshake
        PairingState.Reason.WRONG_PHONE -> R.string.pairing_failed_wrong_phone
        PairingState.Reason.CANCELLED_HERE -> R.string.pairing_failed_cancelled_here
        PairingState.Reason.CANCELLED_THERE -> R.string.pairing_failed_cancelled_there
        PairingState.Reason.PROTOCOL -> R.string.pairing_failed_protocol
    }

private val CAMERA = arrayOf(Manifest.permission.CAMERA)
