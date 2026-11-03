package com.netwatch.app.monitor

import android.content.Context
import com.netwatch.app.data.Prefs
import com.netwatch.app.data.Settings
import com.netwatch.app.router.DeviceResolver
import com.netwatch.app.router.RouterParser
import com.netwatch.app.ssh.DeviceKey
import com.netwatch.app.ssh.HostKeyChangedException
import com.netwatch.app.ssh.RouterSsh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

sealed interface Status {
    data object NeedsSetup : Status
    data object Idle : Status
    data object Connecting : Status
    data object Live : Status
    data class Error(val message: String) : Status
}

data class HistoryState(
    val loading: Boolean = false,
    val intervals: List<HistoryInterval> = emptyList(),
    val error: String? = null,
    val loadedAtMs: Long = 0,
)

data class RecorderStatus(val running: Boolean, val samples: Int, val sinceEpoch: Long)

data class MonitorState(
    val status: Status = Status.Idle,
    val frame: LiveFrame? = null,
    /** Device keys (MACs) never marked as known. */
    val newDevices: Set<String> = emptySet(),
    /** LAN IP -> best name, for labelling history. */
    val names: Map<String, String> = emptyMap(),
    val history: HistoryState = HistoryState(),
)

/**
 * App-wide monitor. Polls the router while anything holds it ([acquire]): the visible UI or
 * the background monitoring service. All SSH work runs on [Dispatchers.IO].
 */
class Monitor(private val context: Context, val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = MonitorEngine()
    private val sshLock = Mutex()
    private val holders = HashSet<String>()
    private var pollJob: Job? = null
    private var ssh: RouterSsh? = null
    private var sshFor: ConnKey? = null
    private var preparedConnection: RouterSsh? = null
    private var clientDatabase: Map<String, Pair<String?, String?>> = emptyMap()

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    val engineTimeline get() = engine.timeline()
    fun deviceSeries(key: String) = engine.series(key)

    private val script: String by lazy { context.assets.open("nw.sh").bufferedReader().readText() }
    private val scriptVersion: String by lazy {
        Regex("""NW_VERSION=(\d+)""").find(script)?.groupValues?.get(1) ?: "0"
    }

    @Synchronized
    fun acquire(tag: String) {
        holders += tag
        if (pollJob?.isActive != true) pollJob = scope.launch { pollLoop() }
    }

    @Synchronized
    fun release(tag: String) {
        holders -= tag
        if (holders.isEmpty()) {
            pollJob?.cancel()
            pollJob = null
            _state.update { if (it.status is Status.Live) it.copy(status = Status.Idle) else it }
        }
    }

    /** Called after the connection settings change: drop the session and start over. */
    fun reconnect() {
        scope.launch {
            sshLock.withLock { closeSsh() }
            engine.reset()
            _state.update { it.copy(frame = null) }
            synchronized(this@Monitor) {
                if (holders.isNotEmpty()) {
                    pollJob?.cancel()
                    pollJob = scope.launch { pollLoop() }
                }
            }
        }
    }

    private suspend fun pollLoop() {
        var failures = 0
        while (scope.isActive) {
            val settings = prefs.current()
            if (!settings.configured || !DeviceKey.exists()) {
                _state.update { it.copy(status = Status.NeedsSetup) }
                delay(2_000)
                continue
            }
            try {
                if (_state.value.status !is Status.Live) _state.update { it.copy(status = Status.Connecting) }
                pollOnce(settings)
                failures = 0
                delay(settings.pollSeconds * 1000L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                sshLock.withLock { closeSsh() }
                _state.update { it.copy(status = Status.Error(describe(e))) }
                delay(minOf(30_000L, 3_000L * failures))
            }
        }
    }

    private suspend fun pollOnce(settings: Settings) {
        val routerMs = measureRouterRtt(settings.host)
        val text = withRouter(settings) { it.exec("sh $SCRIPT snapshot ping") }
        val snap = RouterParser.parseSnapshot(text)
        if (snap.scriptVersion.toString() != scriptVersion) {
            // The router rebooted mid-session (RAM wiped) or an old script is there.
            sshLock.withLock { preparedConnection = null }
            return
        }
        val devices = DeviceResolver.resolve(snap, clientDatabase)
        val frame = engine.update(snap, devices, routerMs)

        if (!settings.knownInitialised && devices.isNotEmpty()) {
            prefs.markKnown(devices.mapNotNull { it.mac })
        }
        val known = prefs.current().knownDevices
        _state.update { s ->
            s.copy(
                status = Status.Live,
                frame = frame ?: s.frame,
                newDevices = devices.filter { it.mac != null && it.mac !in known && it.online }.map { it.key }.toSet(),
                names = devices.associate { it.ip to it.name },
            )
        }
    }

    /** Runs [block] on a connected, prepared session (script uploaded, recorder started). */
    private suspend fun <T> withRouter(settings: Settings, block: (RouterSsh) -> T): T = withContext(Dispatchers.IO) {
        sshLock.withLock {
            val s = connection(settings)
            if (preparedConnection !== s) {
                prepare(s, settings)
                preparedConnection = s
            }
            block(s)
        }
    }

    private suspend fun connection(settings: Settings): RouterSsh {
        val current = ssh
        val key = ConnKey(settings.host, settings.port, settings.user)
        if (current != null && current.isConnected && sshFor == key) return current
        closeSsh()
        val s = RouterSsh(
            host = settings.host,
            port = settings.port,
            user = settings.user,
            identity = DeviceKey.identity(),
            pinnedHostKey = settings.hostKey,
            onHostKeyPinned = { key -> scope.launch { prefs.pinHostKey(key) } },
        )
        s.connect()
        ssh = s
        sshFor = key
        return s
    }

    private fun closeSsh() {
        ssh?.disconnect()
        ssh = null
        sshFor = null
        preparedConnection = null
    }

    private fun prepare(s: RouterSsh, settings: Settings) {
        val have = runCatching { s.exec("sh $SCRIPT version 2>/dev/null").trim() }.getOrDefault("")
        if (have != scriptVersion) {
            s.exec("mkdir -p $DIR && cat > $SCRIPT.tmp && mv $SCRIPT.tmp $SCRIPT", stdin = script.toByteArray())
            // A recorder still running the old script must be restarted to pick up the new one.
            s.exec("sh $SCRIPT stop")
        }
        if (settings.autoRecorder) s.exec("sh $SCRIPT start ${settings.recorderSeconds}")
        clientDatabase = RouterParser.parseClientDatabase(s.exec("sh $SCRIPT clients"))
    }

    private fun measureRouterRtt(host: String): Long? = runCatching {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, 80), 1_500)
            (System.nanoTime() - start) / 1_000_000
        }
    }.getOrNull()

    // ---- one-off actions --------------------------------------------------------------

    /** Connects with the current settings and returns a one-line summary, or throws. */
    suspend fun testConnection(): String {
        val settings = prefs.current()
        val text = withRouter(settings) { it.exec("sh $SCRIPT snapshot") }
        val snap = RouterParser.parseSnapshot(text)
        val devices = DeviceResolver.resolve(snap, clientDatabase)
        reconnect()
        return "Connected. ${devices.count { it.online }} devices online, WAN ${snap.wanIf ?: "?"}, " +
            "LTE ${snap.lte.band ?: "?"} SINR ${snap.lte.sinr ?: "?"} dB."
    }

    fun loadHistory() {
        scope.launch {
            _state.update { it.copy(history = it.history.copy(loading = true, error = null)) }
            try {
                val settings = prefs.current()
                val text = withRouter(settings) { it.exec("sh $SCRIPT history", timeoutMs = 60_000) }
                val intervals = HistoryAnalyzer.intervals(RouterParser.parseHistory(text))
                _state.update {
                    it.copy(history = HistoryState(intervals = intervals, loadedAtMs = System.currentTimeMillis()))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(history = it.history.copy(loading = false, error = describe(e))) }
            }
        }
    }

    suspend fun recorderStatus(): RecorderStatus {
        val line = withRouter(prefs.current()) { it.exec("sh $SCRIPT status") }.trim()
        val f = line.removePrefix("S ").split(' ')
        return RecorderStatus(f.getOrNull(0) == "1", f.getOrNull(1)?.toIntOrNull() ?: 0, f.getOrNull(2)?.toLongOrNull() ?: 0)
    }

    suspend fun startRecorder() {
        val s = prefs.current()
        withRouter(s) { it.exec("sh $SCRIPT stop; sh $SCRIPT start ${s.recorderSeconds}") }
    }

    suspend fun stopRecorder() {
        withRouter(prefs.current()) { it.exec("sh $SCRIPT stop") }
    }

    /** Stops the recorder and deletes every NetWatch rule. The script itself stays in RAM until reboot. */
    suspend fun removeFromRouter() {
        prefs.setAutoRecorder(false)
        withRouter(prefs.current()) { it.exec("sh $SCRIPT remove") }
        engine.reset()
    }

    suspend fun markKnown(macs: Collection<String>) {
        prefs.markKnown(macs)
        val known = prefs.current().knownDevices
        _state.update { s -> s.copy(newDevices = s.newDevices.filterNot { it in known }.toSet()) }
    }

    private data class ConnKey(val host: String, val port: Int, val user: String)

    companion object {
        private const val DIR = "/tmp/netwatch"
        private const val SCRIPT = "$DIR/nw.sh"

        fun describe(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.toList()
            chain.firstOrNull { it is HostKeyChangedException }?.let { return it.message ?: "Host key changed" }
            val msg = chain.joinToString(" | ") { it.message ?: it.javaClass.simpleName }
            return when {
                "Auth fail" in msg || "Auth cancel" in msg ->
                    "The router refused this phone's key. Paste the key from Setup into the router's Authorized Keys and click Apply."
                "timeout" in msg.lowercase() || "connect" in msg.lowercase() && "refused" in msg.lowercase() ->
                    "Can't reach the router's SSH. Is the phone on the home Wi-Fi and SSH set to LAN only?"
                else -> msg
            }
        }
    }
}
