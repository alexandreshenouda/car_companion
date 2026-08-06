package com.carlauncher.companion.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Static, non-interactive route preview — a single accent-colored line tracing a GPS path,
 * normalized (uniform scale, aspect preserved) to fit the given size. Deliberately not a real
 * map: this is drawn for every visible card in a scrolling feed, where one live osmdroid
 * `MapView` per card (as [TraceMap] uses for a single full-screen trace) would be a real
 * perf/tile-bandwidth cost.
 */
@Composable
fun RouteSketch(points: List<Pair<Double, Double>>, accent: Color, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    Canvas(modifier.fillMaxSize()) {
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLng = points.minOf { it.second }
        val maxLng = points.maxOf { it.second }
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-6).toFloat()
        val lngSpan = (maxLng - minLng).coerceAtLeast(1e-6).toFloat()

        val padding = size.minDimension * 0.12f
        val drawWidth = size.width - padding * 2
        val drawHeight = size.height - padding * 2
        val scale = minOf(drawWidth / lngSpan, drawHeight / latSpan)
        val contentWidth = lngSpan * scale
        val contentHeight = latSpan * scale
        val offsetX = padding + (drawWidth - contentWidth) / 2f
        val offsetY = padding + (drawHeight - contentHeight) / 2f

        fun project(lat: Double, lng: Double) = Offset(
            x = offsetX + (lng - minLng).toFloat() * scale,
            y = offsetY + (maxLat - lat).toFloat() * scale,
        )

        val path = Path().apply {
            val (lat0, lng0) = points.first()
            moveTo(project(lat0, lng0).x, project(lat0, lng0).y)
            for (i in 1 until points.size) {
                val (lat, lng) = points[i]
                val p = project(lat, lng)
                lineTo(p.x, p.y)
            }
        }
        drawPath(
            path,
            color = accent,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
