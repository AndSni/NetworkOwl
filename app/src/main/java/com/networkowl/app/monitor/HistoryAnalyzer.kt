package com.networkowl.app.monitor

import com.networkowl.app.router.HistoryPoint

/** Traffic and link quality between two consecutive recorder samples. */
data class HistoryInterval(
    val startMs: Long,
    val endMs: Long,
    val wanDownBps: Double,
    val wanUpBps: Double,
    val sinr: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val band: String?,
    val lossPercent: Int?,
    val avgMs: Double?,
    /** LAN IP -> bytes moved in this interval. */
    val devices: Map<String, IntervalBytes>,
) {
    /** Internet looked bad: packets lost, or the ping was slow. */
    val slow: Boolean
        get() = (lossPercent ?: 0) > 0 || (avgMs ?: 0.0) > SLOW_MS

    companion object {
        const val SLOW_MS = 150.0
    }
}

data class IntervalBytes(val down: Long, val up: Long, val connections: Int)

object HistoryAnalyzer {

    /** Gaps longer than this (recorder stopped, router rebooted) are not bridged. */
    private const val MAX_GAP_S = 300

    fun intervals(points: List<HistoryPoint>): List<HistoryInterval> =
        points.zipWithNext().mapNotNull { (a, b) ->
            val dt = (b.epoch - a.epoch).toDouble()
            if (dt <= 0 || dt > MAX_GAP_S) return@mapNotNull null
            val devices = b.devices.mapValues { (ip, cur) ->
                val old = a.devices[ip]
                IntervalBytes(
                    down = MonitorEngine.delta(cur.down, old?.down ?: cur.down),
                    up = MonitorEngine.delta(cur.up, old?.up ?: cur.up),
                    connections = cur.connections,
                )
            }
            HistoryInterval(
                startMs = a.epoch * 1000,
                endMs = b.epoch * 1000,
                wanDownBps = MonitorEngine.interfaceDelta(b.wanRx, a.wanRx) / dt,
                wanUpBps = MonitorEngine.interfaceDelta(b.wanTx, a.wanTx) / dt,
                sinr = b.sinr,
                rsrp = b.rsrp,
                rsrq = b.rsrq,
                band = b.band,
                lossPercent = b.lossPercent,
                avgMs = b.avgMs,
                devices = devices,
            )
        }

    /** Per-IP totals over a set of intervals, biggest first. */
    fun totals(intervals: List<HistoryInterval>): List<Pair<String, IntervalBytes>> {
        val acc = HashMap<String, LongArray>()
        val conns = HashMap<String, Int>()
        for (i in intervals) for ((ip, b) in i.devices) {
            val a = acc.getOrPut(ip) { LongArray(2) }
            a[0] += b.down
            a[1] += b.up
            conns[ip] = maxOf(conns[ip] ?: 0, b.connections)
        }
        return acc.map { (ip, a) -> ip to IntervalBytes(a[0], a[1], conns[ip] ?: 0) }
            .filter { it.second.down + it.second.up > 0 }
            .sortedByDescending { it.second.down + it.second.up }
    }
}
