package com.misrutas.ev.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.misrutas.ev.data.Snapshot
import kotlin.math.cos

/** Barras verticales con etiqueta debajo y valor encima. */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Double>,
    valueFmt: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val bottom = 18.dp.toPx()
        val top = 16.dp.toPx()
        val plotH = size.height - bottom - top
        val slot = size.width / values.size
        val barW = slot * 0.62f
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barW) / 2
            drawRoundRect(track, Offset(x, top), Size(barW, plotH), CornerRadius(4.dp.toPx()))
            val h = (v.coerceAtLeast(0.0) / max * plotH).toFloat()
            if (h > 0f) {
                drawRoundRect(bar, Offset(x, top + plotH - h), Size(barW, h), CornerRadius(4.dp.toPx()))
            }
            val lbl = tm.measure(labels[i], style)
            drawText(lbl, topLeft = Offset(i * slot + (slot - lbl.size.width) / 2, size.height - lbl.size.height))
            if (v > 0) {
                val vt = tm.measure(valueFmt(v), style)
                drawText(vt, topLeft = Offset(i * slot + (slot - vt.size.width) / 2, (top + plotH - h - vt.size.height).coerceAtLeast(0f)))
            }
        }
    }
}

/** Nube de puntos con ejes, tres líneas guía y recta de tendencia opcional; [connect] la convierte en línea. */
@Composable
fun XYChart(
    points: List<Pair<Double, Double>>,
    xFmt: (Double) -> String,
    yFmt: (Double) -> String,
    modifier: Modifier = Modifier,
    connect: Boolean = false,
    trend: Boolean = false,
    height: Dp = 200.dp,
) {
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val dot = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val trendColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (points.isEmpty()) return@Canvas
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        var xMin = xs.min(); var xMax = xs.max()
        var yMin = minOf(0.0, ys.min()); var yMax = ys.max()
        if (xMax - xMin < 1e-9) { xMin -= 1; xMax += 1 }
        if (yMax - yMin < 1e-9) { yMax += 1 }
        yMax += (yMax - yMin) * 0.08

        val left = 40.dp.toPx(); val bottom = 20.dp.toPx(); val top = 6.dp.toPx(); val right = 8.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        fun px(x: Double) = left + ((x - xMin) / (xMax - xMin) * w).toFloat()
        fun py(y: Double) = top + h - ((y - yMin) / (yMax - yMin) * h).toFloat()

        for (k in 0..3) {
            val yv = yMin + (yMax - yMin) * k / 3
            val y = py(yv)
            drawLine(grid, Offset(left, y), Offset(left + w, y), strokeWidth = 1f,
                pathEffect = if (k == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val t = tm.measure(yFmt(yv), style)
            drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y - t.size.height / 2))
        }
        val x0 = tm.measure(xFmt(xMin), style)
        drawText(x0, topLeft = Offset(left, size.height - x0.size.height))
        val x1 = tm.measure(xFmt(xMax), style)
        drawText(x1, topLeft = Offset(left + w - x1.size.width, size.height - x1.size.height))

        if (connect && points.size > 1) {
            val path = Path()
            points.sortedBy { it.first }.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
            }
            drawPath(path, dot, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            points.forEach { (x, y) -> drawCircle(dot.copy(alpha = 0.75f), 4.dp.toPx(), Offset(px(x), py(y))) }
        }

        if (trend && points.size >= 3) {
            val mx = xs.average(); val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            if (sxx > 0) {
                val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
                val b = my - slope * mx
                drawLine(trendColor, Offset(px(xMin), py(slope * xMin + b)), Offset(px(xMax), py(slope * xMax + b)),
                    strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            }
        }
    }
}

/** Dibuja el recorrido a partir de las coordenadas registradas (sin mapa de fondo). */
@Composable
fun RouteTrace(samples: List<Snapshot>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val startC = Color(0xFF2E7D32)
    val endC = Color(0xFFC62828)
    val pts = samples.mapNotNull { s -> if (s.lat != null && s.lon != null) s.lat to s.lon else null }
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        if (pts.size < 2) return@Canvas
        val latMid = pts.map { it.first }.average()
        val k = cos(Math.toRadians(latMid))
        val xs = pts.map { it.second * k }
        val ys = pts.map { it.first }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-6)
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-6)
        val pad = 16.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun p(i: Int) = Offset(
            (offX + (xs[i] - xs.min()) * scale).toFloat(),
            (size.height - offY - (ys[i] - ys.min()) * scale).toFloat(),
        )
        val path = Path()
        pts.indices.forEach { i -> val o = p(i); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, line, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(startC, 7.dp.toPx(), p(0))
        drawCircle(endC, 7.dp.toPx(), p(pts.lastIndex))
    }
}
