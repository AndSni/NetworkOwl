package com.networkowl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.networkowl.app.monitor.HistoryAnalyzer
import com.networkowl.app.monitor.HistoryInterval
import com.networkowl.app.monitor.HistoryState

private data class Bucket(val intervals: List<HistoryInterval>) {
    val startMs get() = intervals.first().startMs
    val endMs get() = intervals.last().endMs
    private val span get() = intervals.sumOf { it.endMs - it.startMs }.coerceAtLeast(1) / 1000.0
    val downBps get() = intervals.sumOf { it.wanDownBps * (it.endMs - it.startMs) / 1000.0 } / span
    val upBps get() = intervals.sumOf { it.wanUpBps * (it.endMs - it.startMs) / 1000.0 } / span
    val worstMs get() = intervals.mapNotNull { it.avgMs }.maxOrNull()
    val maxLoss get() = intervals.mapNotNull { it.lossPercent }.maxOrNull()
    val minSinr get() = intervals.mapNotNull { it.sinr }.minOrNull()
    val minRsrp get() = intervals.mapNotNull { it.rsrp }.minOrNull()
    val slow get() = intervals.any { it.slow }
    val bands get() = intervals.mapNotNull { it.band }.distinct()
}

private const val MAX_BUCKETS = 240

private fun bucketize(intervals: List<HistoryInterval>): List<Bucket> {
    if (intervals.isEmpty()) return emptyList()
    val per = (intervals.size + MAX_BUCKETS - 1) / MAX_BUCKETS
    return intervals.chunked(per).map { Bucket(it) }
}

@Composable
fun HistoryScreen(
    history: HistoryState,
    names: Map<String, String>,
    recorderRunning: Boolean?,
    onLoad: () -> Unit,
) {
    var rangeHours by rememberSaveable { mutableStateOf(6) }
    var selected by remember(history.loadedAtMs, rangeHours) { mutableStateOf<Int?>(null) }

    val all = history.intervals
    val end = all.lastOrNull()?.endMs ?: 0
    val inRange = if (rangeHours == 0) all else all.filter { it.endMs >= end - rangeHours * 3_600_000L }
    val buckets = remember(history.loadedAtMs, rangeHours) { bucketize(inRange) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Router history", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        all.isEmpty() -> "Recorded on the router every 30 s, kept for 48 h, cleared by a reboot."
                        else -> "${Format.dayClock(all.first().startMs)} – ${Format.dayClock(end)}" +
                            if (recorderRunning == false) " · recorder is off" else ""
                    },
                    color = Palette.muted, fontSize = 12.sp,
                )
            }
            if (history.loading) CircularProgressIndicator(Modifier.padding(8.dp)) else TextButton(onClick = onLoad) { Text("Refresh") }
        }
        history.error?.let { Banner(it, null, {}, color = Palette.bad) }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "1 h", 6 to "6 h", 24 to "24 h", 0 to "All").forEach { (h, label) ->
                FilterChip(selected = rangeHours == h, onClick = { rangeHours = h }, label = { Text(label) })
            }
        }

        if (buckets.isEmpty()) {
            if (!history.loading) Text(
                "No history yet. The recorder adds a sample every 30 seconds; check back in a few minutes.",
                color = Palette.muted,
            )
            return@Column
        }

        Summary(inRange)

        val marks = buckets.map { it.slow }
        NwCard {
            LineChart(
                series = listOf(
                    ChartSeries("↓ Mb/s", Palette.down, buckets.map { Format.mbps(it.downBps) }),
                    ChartSeries("↑ Mb/s", Palette.up, buckets.map { Format.mbps(it.upBps) }),
                ),
                marks = marks, selected = selected, onSelect = { selected = it },
            )
        }
        NwCard {
            LineChart(
                series = listOf(ChartSeries("Ping", Palette.warn, buckets.map { it.worstMs?.toFloat() })),
                unit = " ms", marks = marks, selected = selected, onSelect = { selected = it },
                reference = HistoryInterval.SLOW_MS.toFloat(),
            )
        }
        NwCard {
            LineChart(
                series = listOf(ChartSeries("SINR", Palette.up, buckets.map { it.minSinr?.toFloat() })),
                unit = " dB", marks = marks, selected = selected, onSelect = { selected = it },
                reference = 0f, floorZero = false,
            )
        }
        Text(
            "Red columns: ping over ${HistoryInterval.SLOW_MS.toInt()} ms or lost packets. Dashed SINR line: 0 dB, " +
                "below it interference is stronger than the signal. Tap a chart to see that moment.",
            color = Palette.muted, fontSize = 11.sp,
        )

        val sel = selected?.let { buckets.getOrNull(it) }
        if (sel != null) {
            Moment(sel, names)
        } else {
            NwCard {
                Text("Top devices in this range", fontWeight = FontWeight.Medium)
                DeviceTotals(inRange, names, limit = 10)
            }
        }
    }
}

@Composable
private fun Summary(intervals: List<HistoryInterval>) {
    val slowMs = intervals.filter { it.slow }.sumOf { it.endMs - it.startMs }
    val pings = intervals.mapNotNull { it.avgMs }.sorted()
    val sinrs = intervals.mapNotNull { it.sinr }
    NwCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Stat("Slow", Format.duration(slowMs), if (slowMs > 0) Palette.bad else Palette.good, Modifier.weight(1f))
            Stat("Median ping", pings.getOrNull(pings.size / 2)?.let { "${it.toInt()} ms" } ?: "–", Palette.text, Modifier.weight(1f))
            Stat(
                "SINR range",
                if (sinrs.isEmpty()) "–" else "${sinrs.min()}…${sinrs.max()} dB",
                if ((sinrs.minOrNull() ?: 99) < 0) Palette.warn else Palette.text,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.SemiBold)
        Text(label, color = Palette.muted, fontSize = 11.sp)
    }
}

@Composable
private fun Moment(b: Bucket, names: Map<String, String>) {
    NwCard {
        Text(
            "${Format.dayClock(b.startMs)} – ${Format.clock(b.endMs)}",
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            listOfNotNull(
                "↓ ${Format.rate(b.downBps)} ↑ ${Format.rate(b.upBps)}",
                b.worstMs?.let { "ping up to ${it.toInt()} ms" },
                b.maxLoss?.takeIf { it > 0 }?.let { "$it% loss" },
                b.minSinr?.let { "SINR $it dB" },
                b.minRsrp?.let { "RSRP $it dBm" },
                b.bands.takeIf { it.isNotEmpty() }?.joinToString("/") { it.replace("BAND ", "B") },
            ).joinToString(" · "),
            color = Palette.muted, fontSize = 12.sp,
        )
        DeviceTotals(b.intervals, names, limit = 8)
    }
}

@Composable
private fun DeviceTotals(intervals: List<HistoryInterval>, names: Map<String, String>, limit: Int) {
    val totals = HistoryAnalyzer.totals(intervals).take(limit)
    if (totals.isEmpty()) {
        Text("No device traffic recorded.", color = Palette.muted, fontSize = 12.sp)
        return
    }
    totals.forEach { (ip, t) ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(names[ip] ?: ip, fontSize = 14.sp)
                Text(
                    "$ip · up to ${t.connections} conn",
                    color = if (t.connections >= MANY_CONNECTIONS) Palette.bad else Palette.muted, fontSize = 11.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↓ " + Format.bytes(t.down), color = Palette.down, fontSize = 13.sp)
                Text("↑ " + Format.bytes(t.up), color = Palette.up, fontSize = 13.sp)
            }
        }
    }
}
