package com.netwatch.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

data class ChartSeries(val label: String, val color: Color, val values: List<Float?>)

/**
 * A plain line chart. [marks] shades columns red (e.g. slow internet), [reference] draws a
 * dashed horizontal line, and tapping reports the nearest column index through [onSelect].
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    unit: String = "",
    marks: List<Boolean> = emptyList(),
    reference: Float? = null,
    floorZero: Boolean = true,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
) {
    val n = series.maxOfOrNull { it.values.size } ?: 0
    val all = series.flatMap { s -> s.values.filterNotNull() } + listOfNotNull(reference)
    var lo = if (floorZero) 0f else (all.minOrNull() ?: 0f)
    var hi = all.maxOrNull() ?: 1f
    if (hi - lo < 1e-3f) { hi += 1f; lo -= if (floorZero) 0f else 1f }
    val pad = (hi - lo) * 0.08f
    if (!floorZero) lo -= pad
    hi += pad

    Column(modifier) {
        Row {
            series.forEach { s ->
                val last = s.values.lastOrNull { it != null }
                Text(
                    "● ${s.label}" + (last?.let { "  " + fmt(it) + unit } ?: ""),
                    color = s.color, fontSize = 12.sp,
                )
                Spacer(Modifier.width(12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(Modifier.width(40.dp).height(height)) {
                Text(fmt(hi), color = Palette.muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(fmt(lo), color = Palette.muted, fontSize = 10.sp)
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(height)
                    .then(
                        if (onSelect != null && n > 0) Modifier.pointerInput(n) {
                            detectTapGestures { p ->
                                val i = ((p.x / size.width) * (n - 1)).toInt().coerceIn(0, n - 1)
                                onSelect(i)
                            }
                        } else Modifier
                    )
            ) {
                val w = size.width
                val h = size.height
                fun x(i: Int) = if (n <= 1) w / 2 else i * w / (n - 1)
                fun y(v: Float) = h - (v - lo) / (hi - lo) * h

                drawRect(Palette.surfaceHigh, size = Size(w, h))
                if (n > 1) {
                    val colW = w / (n - 1)
                    marks.forEachIndexed { i, m ->
                        if (m) drawRect(
                            Palette.bad.copy(alpha = 0.28f),
                            topLeft = Offset(x(i) - colW / 2, 0f),
                            size = Size(colW, h),
                        )
                    }
                }
                reference?.let {
                    drawLine(
                        Palette.muted, Offset(0f, y(it)), Offset(w, y(it)), strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                }
                series.forEach { s ->
                    val path = Path()
                    var open = false
                    s.values.forEachIndexed { i, v ->
                        if (v == null) {
                            open = false
                        } else if (!open) {
                            path.moveTo(x(i), y(v)); open = true
                        } else {
                            path.lineTo(x(i), y(v))
                        }
                    }
                    drawPath(path, s.color, style = Stroke(width = 3f))
                    if (n == 1) s.values.firstOrNull()?.let { drawCircle(s.color, 5f, Offset(x(0), y(it))) }
                }
                selected?.takeIf { it in 0 until n }?.let {
                    drawLine(Palette.text, Offset(x(it), 0f), Offset(x(it), h), strokeWidth = 2f)
                }
            }
        }
    }
}

private fun fmt(v: Float): String = when {
    kotlin.math.abs(v) >= 100 -> String.format(Locale.US, "%.0f", v)
    kotlin.math.abs(v) >= 10 -> String.format(Locale.US, "%.0f", v)
    else -> String.format(Locale.US, "%.1f", v)
}
