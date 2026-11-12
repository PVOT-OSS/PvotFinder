// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.device

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.prauga.finder.bluetooth.RssiSample

private const val MIN_DBM = -100f
private const val MAX_DBM = -20f

/** RSSI over the last [windowMillis] up to [now], stronger signal higher, clamped to -100..-20 dBm. */
@Composable
fun RssiChart(
    samples: List<RssiSample>,
    now: Long,
    windowMillis: Long,
    description: String,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.semantics { contentDescription = description }) {
        val stroke = 1.dp.toPx()
        for (dbm in listOf(-90f, -70f, -50f)) {
            val y = size.height * (MAX_DBM - dbm) / (MAX_DBM - MIN_DBM)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
        val start = now - windowMillis
        val points =
            samples
                .filter { it.elapsedMillis >= start }
                .map {
                    val x = size.width * (it.elapsedMillis - start) / windowMillis.toFloat()
                    val dbm = it.rssi.toFloat().coerceIn(MIN_DBM, MAX_DBM)
                    Offset(x, size.height * (MAX_DBM - dbm) / (MAX_DBM - MIN_DBM))
                }
        if (points.isEmpty()) return@Canvas
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path,
            line,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(line, radius = 3.dp.toPx(), center = points.last())
    }
}
