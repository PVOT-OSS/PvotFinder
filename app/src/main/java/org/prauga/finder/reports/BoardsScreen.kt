// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.R
import org.prauga.finder.SubScreen

/** The notice boards this phone posts to and reads from; none until the user adds one. */
@Composable
fun BoardsScreen(settings: ReportSettings, onBack: () -> Unit) {
    val boards by settings.boards.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val add = {
        if (settings.addBoard(text)) text = "" else invalid = true
    }

    SubScreen(stringResource(R.string.boards), onBack) {
        item {
            Text(
                stringResource(R.string.boards_intro),
                modifier = Modifier.padding(horizontal = PvotSpacing.XS),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (boards.isNotEmpty()) {
            item {
                PvotListGroup {
                    boards.forEach { board ->
                        item {
                            PvotListRow(
                                title = board,
                                trailing = {
                                    PvotIconButton(
                                        painterResource(R.drawable.ic_delete),
                                        stringResource(R.string.boards_remove),
                                        { settings.removeBoard(board) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            PvotCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(PvotSpacing.L),
                    verticalArrangement = Arrangement.spacedBy(PvotSpacing.M),
                ) {
                    PvotTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            invalid = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.boards_address),
                        placeholder = "wss://",
                        supportingText = if (invalid) stringResource(R.string.boards_invalid) else null,
                        isError = invalid,
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) add() }),
                    )
                    PvotButton(
                        stringResource(R.string.boards_add),
                        add,
                        Modifier.fillMaxWidth(),
                        enabled = text.isNotBlank(),
                    )
                }
            }
        }
    }
}
