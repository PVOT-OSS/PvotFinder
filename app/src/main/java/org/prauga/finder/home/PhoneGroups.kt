// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotPreferenceRow
import kotlinx.coroutines.launch
import org.prauga.finder.R
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.identity.Peer
import org.prauga.finder.peers.NameDialog
import org.prauga.finder.peers.pairedSummary

@Composable
fun MyPhonesGroup(peers: List<Peer>, onAddPhone: () -> Unit, onOpenPeer: (String) -> Unit) {
    PvotListGroup(title = stringResource(R.string.section_my_phones)) {
        peers.forEach { peer ->
            item {
                PvotPreferenceRow(
                    title = peer.name,
                    summary = pairedSummary(peer),
                    onClick = { onOpenPeer(peer.deviceIdHex) },
                )
            }
        }
        item {
            PvotPreferenceRow(
                title = stringResource(R.string.add_phone),
                summary = if (peers.isEmpty()) stringResource(R.string.add_phone_summary) else null,
                onClick = onAddPhone,
            )
        }
    }
}

@Composable
fun ThisPhoneGroup(identity: IdentityRepository) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    LaunchedEffect(version) { name = identity.self().name }

    PvotListGroup(title = stringResource(R.string.section_this_phone)) {
        item {
            PvotPreferenceRow(
                title = stringResource(R.string.name),
                value = name.orEmpty(),
                summary = stringResource(R.string.name_summary),
                onClick = { renaming = true },
            )
        }
        item {
            PvotPreferenceRow(
                title = stringResource(R.string.reset),
                summary = stringResource(R.string.reset_summary),
                showChevron = false,
                onClick = { resetting = true },
            )
        }
    }

    NameDialog(
        visible = renaming,
        title = stringResource(R.string.rename_this_phone),
        initial = name.orEmpty(),
        onDismiss = { renaming = false },
        onSave = { newName ->
            renaming = false
            scope.launch {
                identity.renameSelf(newName)
                version++
            }
        },
    )
    PvotDialog(
        visible = resetting,
        onDismissRequest = { resetting = false },
        title = stringResource(R.string.reset_title),
        message = stringResource(R.string.reset_message),
        destructive = true,
    ) {
        PvotDialogAction(
            stringResource(R.string.reset),
            {
                resetting = false
                scope.launch {
                    identity.reset()
                    version++
                }
            },
            Modifier.fillMaxWidth(),
            style = PvotDialogActionStyle.Destructive,
        )
        PvotDialogAction(stringResource(R.string.cancel), { resetting = false }, Modifier.fillMaxWidth())
    }
}
