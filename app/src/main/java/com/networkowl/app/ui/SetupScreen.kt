package com.networkowl.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.networkowl.app.data.Settings
import com.networkowl.app.monitor.Monitor
import com.networkowl.app.monitor.RecorderStatus
import com.networkowl.app.ssh.DeviceKey
import com.networkowl.app.ssh.EcdsaIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

@Composable
fun SetupScreen(monitor: Monitor, settings: Settings, onRestartRouter: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember(settings.host) { mutableStateOf(settings.host) }
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var user by remember(settings.user) { mutableStateOf(settings.user) }
    var keyLine by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<RecorderStatus?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        keyLine = withContext(Dispatchers.IO) { DeviceKey.publicLine() }
    }
    LaunchedEffect(settings.configured) {
        if (settings.configured) recorder = runCatching { monitor.recorderStatus() }.getOrNull()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Router connection", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        NwCard {
            Step(1, "Turn on SSH in the router: http://${host.ifBlank { "192.168.50.1" }} → Administration → System. " +
                "Enable SSH: LAN only, port 22, Allow Password Login: No.")
            Step(2, "Copy this phone's key and paste it as a new line in Authorized Keys, then click Apply. " +
                "Keep any keys already there.")
            keyLine?.let { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Palette.muted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh).padding(8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copy(context, line) }) { Text("Copy key") }
                }
            }
            Step(3, "Enter the router address and its admin user name, then test.")
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Router address") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it.trim() }, label = { Text("User name") }, singleLine = true,
                    modifier = Modifier.weight(2f))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !testing && host.isNotBlank() && user.isNotBlank(),
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            monitor.prefs.saveConnection(host, port.toIntOrNull() ?: 22, user)
                            testResult = try {
                                true to monitor.testConnection()
                            } catch (e: Exception) {
                                false to Monitor.describe(e)
                            }
                            recorder = runCatching { monitor.recorderStatus() }.getOrNull()
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Save & test") }
            }
            testResult?.let { (ok, msg) ->
                Text(msg, color = if (ok) Palette.good else Palette.bad, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            settings.hostKey?.let { key ->
                Text(
                    "Router identity pinned: " + EcdsaIdentity.fingerprint(Base64.getDecoder().decode(key)),
                    color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Text("Live view", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        NwCard {
            Text("Update every", color = Palette.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 3, 5, 10).forEach { s ->
                    FilterChip(selected = settings.pollSeconds == s, onClick = { scope.launch { monitor.prefs.setPollSeconds(s) } },
                        label = { Text("$s s") })
                }
            }
        }

        Text("Router recorder", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        NwCard {
            Text(
                "Records traffic, LTE signal and ping on the router itself, even while this phone is away. " +
                    "It lives in the router's memory: a reboot clears it, and the app restarts it when it connects.",
                color = Palette.muted, fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Start automatically", modifier = Modifier.weight(1f))
                Switch(settings.autoRecorder, { on -> scope.launch { monitor.prefs.setAutoRecorder(on) } })
            }
            Text("Sample every", color = Palette.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60).forEach { s ->
                    FilterChip(selected = settings.recorderSeconds == s, onClick = { scope.launch { monitor.prefs.setRecorderSeconds(s) } },
                        label = { Text("$s s") })
                }
            }
            recorder?.let {
                Text(
                    (if (it.running) "Recording" else "Stopped") + " · ${it.samples} samples" +
                        (if (it.sinceEpoch > 0) " since ${Format.dayClock(it.sinceEpoch * 1000)}" else ""),
                    color = if (it.running) Palette.good else Palette.warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(enabled = settings.configured, onClick = {
                    scope.launch {
                        runCatching { monitor.startRecorder() }
                        recorder = runCatching { monitor.recorderStatus() }.getOrNull()
                    }
                }) { Text("(Re)start") }
                OutlinedButton(enabled = settings.configured, onClick = {
                    scope.launch {
                        runCatching { monitor.stopRecorder() }
                        recorder = runCatching { monitor.recorderStatus() }.getOrNull()
                    }
                }) { Text("Stop") }
            }
        }

        Text("Router", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        NwCard {
            Text(
                "A clean restart, the same as the router's own Reboot button. Everyone is offline for " +
                    "about 2–3 minutes while the LTE modem reconnects.",
                color = Palette.muted, fontSize = 12.sp,
            )
            OutlinedButton(enabled = settings.configured, onClick = onRestartRouter, modifier = Modifier.padding(top = 6.dp)) {
                Text("Restart router")
            }
        }

        Text("Clean up", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        NwCard {
            Text(
                "Removes NetworkOwl's counting rules and stops the recorder. Nothing NetworkOwl does is saved to the " +
                    "router's flash, so a reboot also removes everything.",
                color = Palette.muted, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(enabled = settings.configured, onClick = { confirmRemove = true }) { Text("Remove from router") }
                OutlinedButton(onClick = {
                    scope.launch { monitor.prefs.pinHostKey(null); monitor.reconnect() }
                }) { Text("Forget router identity") }
            }
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove from router?") },
        text = { Text("Stops the recorder, deletes its history and removes the counting rules. Automatic recorder start is turned off. The counting rules come back whenever the live view runs again.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRemove = false
                scope.launch {
                    val msg = runCatching { monitor.removeFromRouter(); "Removed from router" }.getOrElse { Monitor.describe(it) }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    recorder = runCatching { monitor.recorderStatus() }.getOrNull()
                }
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$n.", fontWeight = FontWeight.Bold, color = Palette.down, modifier = Modifier.padding(end = 8.dp))
        Text(text, fontSize = 13.sp)
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NetworkOwl SSH key", text))
    Toast.makeText(context, "Key copied", Toast.LENGTH_SHORT).show()
}
