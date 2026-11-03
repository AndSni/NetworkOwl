package com.networkowl.app.monitor

import com.networkowl.app.router.ByteCounters
import com.networkowl.app.router.Device
import com.networkowl.app.router.LteInfo
import com.networkowl.app.router.Probe
import com.networkowl.app.router.RawSnapshot

data class DeviceRate(
    val device: Device,
    val downBps: Double,
    val upBps: Double,
    val connections: Int,
    val sessionDown: Long,
    val sessionUp: Long,
)

data class TimelinePoint(
    val epochMs: Long,
    val wanDownBps: Double,
    val wanUpBps: Double,
    val sinr: Int?,
    val rsrp: Int?,
    val internetMs: Double?,
    val internetLoss: Int?,
    val routerMs: Long?,
)

data class DevicePoint(val epochMs: Long, val downBps: Double, val upBps: Double)

data class LiveFrame(
    val epochMs: Long,
    val wanDownBps: Double,
    val wanUpBps: Double,
    /** WAN traffic not matched to any LAN device: the router itself (DNS, updates) and strays. */
    val otherDownBps: Double,
    val otherUpBps: Double,
    val devices: List<DeviceRate>,
    val lte: LteInfo,
    val probe: Probe?,
    val routerMs: Long?,
    val recorderRunning: Boolean,
    val sessionStartMs: Long,
)

/**
 * Turns successive snapshots into rates. Counters are cumulative on the router; a counter
 * that goes backwards means the firewall was rebuilt (rules re-added from zero), so the new
 * value itself is the traffic since then.
 */
class MonitorEngine(private val keep: Int = 1200) {
    private var prev: RawSnapshot? = null
    private var sessionStart = 0L
    private val sessionTotals = HashMap<String, ByteCounters>()
    private val timeline = ArrayDeque<TimelinePoint>()
    private val deviceSeries = HashMap<String, ArrayDeque<DevicePoint>>()

    @Synchronized
    fun reset() {
        prev = null
        sessionStart = 0
        sessionTotals.clear()
        timeline.clear()
        deviceSeries.clear()
    }

    @Synchronized
    fun timeline(): List<TimelinePoint> = timeline.toList()

    @Synchronized
    fun series(key: String): List<DevicePoint> = deviceSeries[key]?.toList() ?: emptyList()

    /** Returns null for the first snapshot (no rates yet) or after a router reboot. */
    @Synchronized
    fun update(snap: RawSnapshot, devices: List<Device>, routerMs: Long?): LiveFrame? {
        val before = prev
        prev = snap
        val nowMs = snap.epoch * 1000
        if (sessionStart == 0L) sessionStart = nowMs
        if (before == null) return null
        val dt = snap.uptime - before.uptime
        if (dt <= 0.2 || before.wanIf != snap.wanIf) return null

        val wanDown = interfaceDelta(snap.wanRx, before.wanRx)
        val wanUp = interfaceDelta(snap.wanTx, before.wanTx)
        var sumDown = 0L
        var sumUp = 0L

        val rates = devices.map { d ->
            val cur = snap.counters[d.ip]
            val old = before.counters[d.ip]
            val down = if (cur != null) delta(cur.down, old?.down ?: cur.down) else 0
            val up = if (cur != null) delta(cur.up, old?.up ?: cur.up) else 0
            sumDown += down
            sumUp += up
            val total = sessionTotals[d.key] ?: ByteCounters(0, 0)
            val newTotal = ByteCounters(total.up + up, total.down + down)
            sessionTotals[d.key] = newTotal
            deviceSeries.getOrPut(d.key) { ArrayDeque() }.pushCapped(DevicePoint(nowMs, down / dt, up / dt))
            DeviceRate(
                device = d,
                downBps = down / dt,
                upBps = up / dt,
                connections = snap.connections[d.ip] ?: 0,
                sessionDown = newTotal.down,
                sessionUp = newTotal.up,
            )
        }.sortedWith(compareByDescending<DeviceRate> { it.downBps + it.upBps }
            .thenByDescending { it.device.online }
            .thenByDescending { it.sessionDown + it.sessionUp }
            .thenBy { it.device.name.lowercase() })

        timeline.pushCapped(
            TimelinePoint(
                epochMs = nowMs,
                wanDownBps = wanDown / dt,
                wanUpBps = wanUp / dt,
                sinr = snap.lte.sinr,
                rsrp = snap.lte.rsrp,
                internetMs = snap.probe?.avgMs,
                internetLoss = snap.probe?.lossPercent,
                routerMs = routerMs,
            )
        )

        return LiveFrame(
            epochMs = nowMs,
            wanDownBps = wanDown / dt,
            wanUpBps = wanUp / dt,
            otherDownBps = (wanDown - sumDown).coerceAtLeast(0) / dt,
            otherUpBps = (wanUp - sumUp).coerceAtLeast(0) / dt,
            devices = rates,
            lte = snap.lte,
            probe = snap.probe,
            routerMs = routerMs,
            recorderRunning = snap.recorderRunning,
            sessionStartMs = sessionStart,
        )
    }

    private fun <T> ArrayDeque<T>.pushCapped(item: T) {
        addLast(item)
        while (size > keep) removeFirst()
    }

    companion object {
        private const val WRAP32 = 1L shl 32

        /** iptables counters are 64-bit; going backwards means the rule was re-created from zero. */
        fun delta(cur: Long, old: Long): Long = if (cur >= old) cur - old else cur

        /**
         * /proc/net/dev counters are 32-bit on this MIPS router (the WAN counter passes 4 GiB
         * within days), so a step backwards from below 2^32 is a wrap, not a reset.
         */
        fun interfaceDelta(cur: Long, old: Long): Long = when {
            cur >= old -> cur - old
            old < WRAP32 -> cur + WRAP32 - old
            else -> cur
        }
    }
}
