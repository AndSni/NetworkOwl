package com.netwatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netwatch.app.monitor.DeviceRate
import com.netwatch.app.monitor.LiveFrame
import com.netwatch.app.monitor.MonitorState
import com.netwatch.app.monitor.Status
import com.netwatch.app.monitor.TimelinePoint
import com.netwatch.app.router.Link
import com.netwatch.app.router.LteInfo

/** Devices with this many tracked connections get flagged; idle phones sit well under 100. */
const val MANY_CONNECTIONS = 300
const val WEAK_RSSI = -75

@Composable
fun LiveScreen(
    state: MonitorState,
    timeline: List<TimelinePoint>,
    sessionRunning: Boolean,
    onToggleSession: (Boolean) -> Unit,
    onOpenDevice: (DeviceRate) -> Unit,
    onMarkAllKnown: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val frame = state.frame
    var showOffline by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { StatusLine(state, onOpenSetup) }

        if (frame != null) {
            item { LinkCard(frame, timeline) }
            item { SessionCard(sessionRunning, onToggleSession, frame) }

            val newOnes = frame.devices.filter { it.device.key in state.newDevices }
            if (newOnes.isNotEmpty()) item {
                Banner(
                    "${newOnes.size} new device${if (newOnes.size > 1) "s" else ""} on your network: " +
                        newOnes.joinToString { it.device.name },
                    action = "Mark known",
                    onAction = onMarkAllKnown,
                )
            }

            val online = frame.devices.filter { it.device.online || it.downBps + it.upBps > 0 }
            val offline = frame.devices - online.toSet()
            item { SectionTitle("Online (${online.size})") }
            items(online, key = { it.device.key }) { d ->
                DeviceRow(d, isNew = d.device.key in state.newDevices, onClick = { onOpenDevice(d) })
            }
            item { OtherRow(frame) }
            if (offline.isNotEmpty()) {
                item {
                    Text(
                        (if (showOffline) "▾ " else "▸ ") + "Offline / recently seen (${offline.size})",
                        color = Palette.muted,
                        modifier = Modifier.fillMaxWidth().clickable { showOffline = !showOffline }.padding(vertical = 8.dp),
                    )
                }
                if (showOffline) items(offline, key = { "off-" + it.device.key }) { d ->
                    DeviceRow(d, isNew = false, onClick = { onOpenDevice(d) })
                }
            }
        } else if (state.status is Status.Live || state.status is Status.Connecting) {
            item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: MonitorState, onOpenSetup: () -> Unit) {
    when (val s = state.status) {
        Status.NeedsSetup -> Banner("Connect NetWatch to your router first.", "Setup", onOpenSetup)
        is Status.Error -> Banner(s.message, "Setup", onOpenSetup, color = Palette.bad)
        Status.Connecting -> Text("Connecting to router…", color = Palette.muted, fontSize = 13.sp)
        Status.Idle -> Text("Paused", color = Palette.muted, fontSize = 13.sp)
        Status.Live -> state.frame?.let {
            Text("Live · updated ${Format.time(it.epochMs)}", color = Palette.muted, fontSize = 13.sp)
        } ?: Text("Measuring…", color = Palette.muted, fontSize = 13.sp)
    }
}

@Composable
private fun LinkCard(f: LiveFrame, timeline: List<TimelinePoint>) {
    NwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Internet (LTE)", color = Palette.muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("↓ " + Format.rate(f.wanDownBps), color = Palette.down, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(14.dp))
                    Text("↑ " + Format.rate(f.wanUpBps), color = Palette.up, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val ping = f.probe?.avgMs
                Text(
                    ping?.let { "${it.toInt()} ms" } ?: "no reply",
                    color = when {
                        ping == null -> Palette.bad
                        ping > 150 -> Palette.bad
                        ping > 70 -> Palette.warn
                        else -> Palette.good
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text("internet ping", color = Palette.muted, fontSize = 11.sp)
                f.routerMs?.let {
                    Text("Wi-Fi→router ${it} ms", color = if (it > 50) Palette.warn else Palette.muted, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LteRow(f.lte)
        if (timeline.size > 2) {
            Spacer(Modifier.height(10.dp))
            LineChart(
                series = listOf(
                    ChartSeries("↓ Mb/s", Palette.down, timeline.map { Format.mbps(it.wanDownBps) }),
                    ChartSeries("↑ Mb/s", Palette.up, timeline.map { Format.mbps(it.wanUpBps) }),
                ),
                height = 80.dp,
                marks = timeline.map { (it.internetLoss ?: 0) > 0 || (it.internetMs ?: 0.0) > 150 },
            )
        }
    }
}

@Composable
private fun LteRow(lte: LteInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Metric("SINR", lte.sinr?.let { "$it dB" }, Palette.grade(lte.sinrGrade), Modifier.weight(1f))
        Metric("RSRP", lte.rsrp?.let { "$it dBm" }, Palette.grade(lte.rsrpGrade), Modifier.weight(1f))
        Metric("RSRQ", lte.rsrq?.let { "$it dB" }, Palette.grade(lte.rsrqGrade), Modifier.weight(1f))
    }
    val bands = listOfNotNull(lte.primaryBand ?: lte.band, lte.secondaryBand).joinToString(" + ") { it.replace("BAND ", "B") }
    Text(
        listOfNotNull(lte.provider, lte.technology, bands.ifBlank { null }, lte.cellId?.let { "cell $it" }).joinToString(" · "),
        color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Metric(label: String, value: String?, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value ?: "–", color = color, fontWeight = FontWeight.SemiBold)
        Text(label, color = Palette.muted, fontSize = 11.sp)
    }
}

@Composable
private fun SessionCard(running: Boolean, onToggle: (Boolean) -> Unit, f: LiveFrame) {
    NwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep monitoring in background", fontWeight = FontWeight.Medium)
                Text(
                    (if (running) "On, until you stop it from the notification. " else "") +
                        "Router recorder: " + (if (f.recorderRunning) "recording" else "off") +
                        " · session ${Format.duration(f.epochMs - f.sessionStartMs)}",
                    color = Palette.muted, fontSize = 12.sp,
                )
            }
            Switch(checked = running, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun DeviceRow(d: DeviceRate, isNew: Boolean, onClick: () -> Unit) {
    val dev = d.device
    NwCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dev.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (dev.online) Palette.text else Palette.muted,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isNew) Chip("NEW", Palette.warn)
                }
                Text(linkText(d), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val flags = flags(d)
                if (flags.isNotEmpty()) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    flags.forEach { (t, c) -> Chip(t, c) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↓ " + Format.rate(d.downBps), color = Palette.down, fontSize = 14.sp)
                Text("↑ " + Format.rate(d.upBps), color = Palette.up, fontSize = 14.sp)
                Text(
                    "Σ ${Format.bytes(d.sessionDown)} / ${Format.bytes(d.sessionUp)}",
                    color = Palette.muted, fontSize = 11.sp,
                )
            }
        }
    }
}

fun linkText(d: DeviceRate): String {
    val dev = d.device
    val link = when (dev.link) {
        Link.WIRED -> "Wired"
        Link.WIFI_2G -> "Wi-Fi 2.4 GHz"
        Link.WIFI_5G -> "Wi-Fi 5 GHz"
        Link.UNKNOWN -> null
    }
    return listOfNotNull(
        dev.ip,
        link,
        dev.rssi?.let { "$it dBm" },
        if (dev.viaBridge) "via extender" else null,
        "${d.connections} conn",
    ).joinToString(" · ")
}

fun flags(d: DeviceRate): List<Pair<String, Color>> = buildList {
    if ((d.device.rssi ?: 0) <= WEAK_RSSI && d.device.rssi != null) add("weak Wi-Fi" to Palette.warn)
    if (d.connections >= MANY_CONNECTIONS) add("many connections" to Palette.bad)
    if (d.upBps > 2 * d.downBps && d.upBps * 8 > 1e6) add("heavy upload" to Palette.warn)
}

@Composable
private fun OtherRow(f: LiveFrame) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("Router itself & other", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            "↓ ${Format.rate(f.otherDownBps)}  ↑ ${Format.rate(f.otherUpBps)}",
            color = Palette.muted, fontSize = 12.sp,
        )
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun NwCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
fun Banner(text: String, action: String?, onAction: () -> Unit, color: Color = Palette.warn) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = color) }
    }
}
