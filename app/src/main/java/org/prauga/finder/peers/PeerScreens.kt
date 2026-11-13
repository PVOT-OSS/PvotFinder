// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.peers

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.components.list.PvotPreferenceRow
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import kotlinx.coroutines.launch
import org.prauga.finder.R
import org.prauga.finder.SubScreen
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.identity.Peer
import org.prauga.finder.pairing.PeerInfo

@Composable
fun PeerScreen(identity: IdentityRepository, deviceIdHex: String, onBack: () -> Unit) {
    val peers by identity.peers.collectAsStateWithLifecycle(null)
    val peer = peers?.firstOrNull { it.deviceIdHex == deviceIdHex }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SubScreen(peer?.name ?: stringResource(R.string.device), onBack) {
        if (peer == null) return@SubScreen
        item {
            PvotListGroup {
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.name),
                        value = peer.name,
                        onClick = { renaming = true },
                    )
                }
                item {
                    PvotListRow(
                        title = stringResource(R.string.paired_on),
                        supportingText =
                            DateUtils.formatDateTime(
                                context,
                                peer.pairedAtMillis,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                            ),
                    )
                }
                item {
                    PvotListRow(
                        title = stringResource(R.string.app_version),
                        supportingText = peer.appVersion,
                    )
                }
            }
        }
        item {
            PvotListGroup {
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.remove_phone),
                        summary = stringResource(R.string.remove_phone_summary),
                        showChevron = false,
                        onClick = { removing = true },
                    )
                }
            }
        }
    }

    if (peer != null) {
        NameDialog(
            visible = renaming,
            title = stringResource(R.string.rename_phone),
            initial = peer.name,
            onDismiss = { renaming = false },
            onSave = { name ->
                renaming = false
                scope.launch { identity.renamePeer(peer.deviceIdHex, name) }
            },
        )
        PvotDialog(
            visible = removing,
            onDismissRequest = { removing = false },
            title = stringResource(R.string.remove_phone_title, peer.name),
            message = stringResource(R.string.remove_phone_message),
            destructive = true,
        ) {
            PvotDialogAction(
                stringResource(R.string.remove),
                {
                    removing = false
                    scope.launch {
                        identity.removePeer(peer.deviceIdHex)
                        onBack()
                    }
                },
                Modifier.fillMaxWidth(),
                style = PvotDialogActionStyle.Destructive,
            )
            PvotDialogAction(
                stringResource(R.string.cancel),
                { removing = false },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun NameDialog(
    visible: Boolean,
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(visible, initial) { mutableStateOf(initial) }
    PvotDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = title,
        content = {
            PvotTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                maxLength = PeerInfo.MAX_TEXT,
            )
        },
    ) {
        PvotDialogAction(
            stringResource(R.string.save),
            { onSave(text) },
            Modifier.fillMaxWidth(),
            style = PvotDialogActionStyle.Primary,
            enabled = text.isNotBlank(),
        )
        PvotDialogAction(stringResource(R.string.cancel), onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
fun pairedSummary(peer: Peer): String =
    stringResource(
        R.string.paired_summary,
        DateUtils.getRelativeTimeSpanString(
                peer.pairedAtMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            .toString(),
    )
