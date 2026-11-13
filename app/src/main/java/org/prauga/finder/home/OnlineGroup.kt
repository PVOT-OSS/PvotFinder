// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotPreferenceRow
import com.prauga.pvot.designsystem.components.list.PvotSwitchRow
import org.prauga.finder.R
import org.prauga.finder.finding.FindableService
import org.prauga.finder.reports.ReportSettings

/** Reporting this phone's own location over the internet, and the boards it uses. */
@Composable
fun OnlineGroup(settings: ReportSettings, findable: Boolean, onOpenBoards: () -> Unit) {
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    val boards by settings.boards.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PvotListGroup(title = stringResource(R.string.section_online)) {
        item {
            PvotSwitchRow(
                title = stringResource(R.string.report_online),
                checked = enabled,
                onCheckedChange = { on ->
                    settings.setEnabled(on)
                    if (findable) FindableService.start(context)
                },
                summary =
                    when {
                        !enabled -> stringResource(R.string.report_online_off)
                        !findable -> stringResource(R.string.report_online_needs_findable)
                        boards.isEmpty() -> stringResource(R.string.report_online_needs_boards)
                        else -> stringResource(R.string.report_online_on)
                    },
            )
        }
        item {
            PvotPreferenceRow(
                title = stringResource(R.string.boards),
                value =
                    if (boards.isEmpty()) stringResource(R.string.boards_none)
                    else pluralStringResource(R.plurals.boards_count, boards.size, boards.size),
                onClick = onOpenBoards,
            )
        }
    }
}
