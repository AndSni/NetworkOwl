package com.networkowl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.networkowl.app.monitor.DevicePoint
import com.networkowl.app.monitor.DeviceRate
import com.networkowl.app.router.Link

@Composable
fun DeviceScreen(
    rate: DeviceRate,
    series: List<DevicePoint>,
    isNew: Boolean,
    onMarkKnown: () -> Unit,
) {
    val d = rate.device
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            Text(d.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (isNew) Chip("NEW", Palette.warn)
        }
        NwCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("↓ " + Format.rate(rate.downBps), color = Palette.down, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("↑ " + Format.rate(rate.upBps), color = Palette.up, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Column {
                    Text("This session", color = Palette.muted, fontSize = 12.sp)
                    Text("↓ " + Format.bytes(rate.sessionDown), color = Palette.down)
                    Text("↑ " + Format.bytes(rate.sessionUp), color = Palette.up)
                }
            }
            if (series.size > 2) {
                Spacer(Modifier.height(10.dp))
                LineChart(
                    series = listOf(
                        ChartSeries("↓ Mb/s", Palette.down, series.map { Format.mbps(it.downBps) }),
                        ChartSeries("↑ Mb/s", Palette.up, series.map { Format.mbps(it.upBps) }),
                    ),
                    height = 140.dp,
                )
                Text(
                    "Since ${Format.time(series.first().epochMs)}",
                    color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        NwCard {
            Detail("IP address", d.ip)
            Detail("MAC address", d.mac ?: "unknown")
            Detail("Maker", d.vendor ?: "unknown")
            Detail(
                "Connection",
                when (d.link) {
                    Link.WIRED -> "Wired"
                    Link.WIFI_2G -> "Wi-Fi 2.4 GHz"
                    Link.WIFI_5G -> "Wi-Fi 5 GHz"
                    Link.UNKNOWN -> "unknown"
                } + (if (d.viaBridge) ", through a range extender" else ""),
            )
            d.rssi?.let { Detail("Wi-Fi signal", "$it dBm" + if (it <= WEAK_RSSI) " (weak)" else "") }
            Detail("Open connections", "${rate.connections}" + if (rate.connections >= MANY_CONNECTIONS) " (unusually many)" else "")
            Detail("Status", if (d.online) "online" else "not seen right now")
        }
        if (d.viaBridge) {
            Text(
                "Devices behind a range extender share its Wi-Fi link to the router, so they slow each other down " +
                    "and the router can't report their own Wi-Fi signal.",
                color = Palette.muted, fontSize = 12.sp,
            )
        }
        if (isNew) OutlinedButton(onClick = onMarkKnown) { Text("I know this device") }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp)
    }
}
