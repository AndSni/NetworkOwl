package com.netwatch.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netwatch.app.monitor.Monitor
import kotlinx.coroutines.launch

@Composable
fun RestartRouterDialog(monitor: Monitor, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keepHistory by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Restart the router?") },
        text = {
            Column {
                Text(
                    "Wi-Fi and internet drop for everyone for about 2–3 minutes while the router " +
                        "restarts and the LTE modem reconnects. NetWatch reconnects by itself.",
                )
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep recorder history")
                        Text(
                            "Saved to the router's flash for the restart, then moved back to memory.",
                            color = Palette.muted, fontSize = 12.sp,
                        )
                    }
                    Switch(keepHistory, { keepHistory = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val error = runCatching { monitor.restartRouter(keepHistory) }.exceptionOrNull()
                    Toast.makeText(
                        context,
                        error?.let { Monitor.describe(it) } ?: "Router is restarting",
                        Toast.LENGTH_LONG,
                    ).show()
                    busy = false
                    onDismiss()
                }
            }) { Text(if (busy) "Restarting…" else "Restart", color = Palette.bad) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
