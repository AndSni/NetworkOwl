package com.netwatch.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netwatch.app.data.Settings
import com.netwatch.app.monitor.MonitorService
import com.netwatch.app.monitor.Status
import com.netwatch.app.ui.DeviceScreen
import com.netwatch.app.ui.HistoryScreen
import com.netwatch.app.ui.LiveScreen
import com.netwatch.app.ui.NetWatchTheme
import com.netwatch.app.ui.Palette
import com.netwatch.app.ui.RestartRouterDialog
import com.netwatch.app.ui.SetupScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val monitor = (application as NetWatchApp).monitor
        setContent { NetWatchTheme { App(monitor) } }
    }
}

private enum class Tab(val label: String) { LIVE("Live"), HISTORY("History"), SETUP("Setup") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(monitor: com.netwatch.app.monitor.Monitor) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by monitor.state.collectAsStateWithLifecycle()
    val settings by monitor.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val sessionRunning by MonitorService.isRunning.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var openDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var askRestart by rememberSaveable { mutableStateOf(false) }

    // Poll while the app is on screen; the monitoring service keeps it going in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> monitor.acquire("ui")
                Lifecycle.Event.ON_STOP -> monitor.release("ui")
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            monitor.release("ui")
        }
    }

    LaunchedEffect(state.status) {
        if (state.status == Status.NeedsSetup && tab == Tab.LIVE) tab = Tab.SETUP
    }
    LaunchedEffect(tab) {
        if (tab == Tab.HISTORY && state.history.loadedAtMs == 0L && settings.configured) monitor.loadHistory()
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        MonitorService.start(context)
    }

    val device = openDevice?.let { key -> state.frame?.devices?.firstOrNull { it.device.key == key } }
    BackHandler(enabled = openDevice != null) { openDevice = null }

    Scaffold(
        containerColor = Palette.background,
        topBar = {
            TopAppBar(
                title = { Text(device?.device?.name ?: "NetWatch") },
                navigationIcon = {
                    if (openDevice != null) IconButton(onClick = { openDevice = null }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background),
            )
        },
        bottomBar = {
            if (openDevice == null) NavigationBar(containerColor = Palette.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        icon = {
                            @Suppress("DEPRECATION")
                            Icon(
                                when (t) {
                                    Tab.LIVE -> Icons.Filled.Home
                                    Tab.HISTORY -> Icons.Filled.List
                                    Tab.SETUP -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                openDevice != null && device != null -> DeviceScreen(
                    rate = device,
                    series = monitor.deviceSeries(device.device.key),
                    isNew = device.device.key in state.newDevices,
                    onMarkKnown = { scope.launch { monitor.markKnown(listOfNotNull(device.device.mac)) } },
                )
                tab == Tab.LIVE -> LiveScreen(
                    state = state,
                    timeline = monitor.engineTimeline,
                    sessionRunning = sessionRunning,
                    onToggleSession = { on ->
                        if (!on) MonitorService.stop(context)
                        else if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else MonitorService.start(context)
                    },
                    onOpenDevice = { openDevice = it.device.key },
                    onMarkAllKnown = {
                        val macs = state.frame?.devices?.filter { it.device.key in state.newDevices }?.mapNotNull { it.device.mac }
                        if (macs != null) scope.launch { monitor.markKnown(macs) }
                    },
                    onOpenSetup = { tab = Tab.SETUP },
                    onRestartRouter = { askRestart = true },
                )
                tab == Tab.HISTORY -> HistoryScreen(
                    history = state.history,
                    names = state.names,
                    recorderRunning = state.frame?.recorderRunning,
                    onLoad = { monitor.loadHistory() },
                )
                else -> SetupScreen(monitor, settings, onRestartRouter = { askRestart = true })
            }
        }
    }

    if (askRestart) RestartRouterDialog(monitor) {
        askRestart = false
        tab = Tab.LIVE
    }
}
