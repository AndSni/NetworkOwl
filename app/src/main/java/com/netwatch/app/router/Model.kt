package com.netwatch.app.router

/** One `nw.sh snapshot` as the router printed it, before any interpretation. */
data class RawSnapshot(
    val scriptVersion: Int,
    val epoch: Long,
    val uptime: Double,
    val wanIf: String?,
    val wanRx: Long,
    val wanTx: Long,
    /** LAN IP -> (upload bytes, download bytes) since the counting rule was added. */
    val counters: Map<String, ByteCounters>,
    val connections: Map<String, Int>,
    val lte: LteInfo,
    /** LAN IP -> MAC as seen in the router's ARP table. */
    val arp: Map<String, String>,
    val leases: List<Lease>,
    /** MAC -> name the owner gave the device in the ASUS UI. */
    val customNames: Map<String, String>,
    /** MAC -> networkmap's live view of the device. */
    val clientCache: Map<String, CachedClient>,
    val recorderRunning: Boolean,
    val probe: Probe?,
)

data class ByteCounters(val up: Long, val down: Long)

data class Lease(val mac: String, val ip: String, val hostname: String?)

data class CachedClient(
    val mac: String,
    val ip: String?,
    val name: String?,
    val nickName: String?,
    val vendor: String?,
    /** ASUS isWL: 0 wired, 1 = 2.4 GHz, 2/3 = 5 GHz. */
    val wifiBand: Int?,
    val rssi: Int?,
    val online: Boolean,
)

data class Probe(val lossPercent: Int, val avgMs: Double?)

data class LteInfo(
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val rssi: Int? = null,
    val bars: Int? = null,
    val band: String? = null,
    val primaryBand: String? = null,
    val secondaryBand: String? = null,
    val technology: String? = null,
    val provider: String? = null,
    val cellId: String? = null,
) {
    /** Rough LTE quality grades, using the usual field-test thresholds. */
    val sinrGrade: Grade? get() = sinr?.let { grade(it, excellent = 20, good = 13, fair = 0) }
    val rsrpGrade: Grade? get() = rsrp?.let { grade(it, excellent = -80, good = -90, fair = -100) }
    val rsrqGrade: Grade? get() = rsrq?.let { grade(it, excellent = -10, good = -15, fair = -20) }

    private fun grade(v: Int, excellent: Int, good: Int, fair: Int) = when {
        v >= excellent -> Grade.EXCELLENT
        v >= good -> Grade.GOOD
        v >= fair -> Grade.FAIR
        else -> Grade.POOR
    }
}

enum class Grade { EXCELLENT, GOOD, FAIR, POOR }

enum class Link { WIRED, WIFI_2G, WIFI_5G, UNKNOWN }

/** A LAN device with everything we know about who it is. */
data class Device(
    val key: String,
    val ip: String,
    val mac: String?,
    val name: String,
    val vendor: String?,
    val link: Link,
    val rssi: Int?,
    /** Its ARP entry is shared with other IPs: it sits behind a range extender or bridge. */
    val viaBridge: Boolean,
    /** Present in the router's ARP table right now. */
    val online: Boolean,
)

/** One line of the router-side recorder's history. */
data class HistoryPoint(
    val epoch: Long,
    val wanRx: Long,
    val wanTx: Long,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val band: String?,
    val lossPercent: Int?,
    val avgMs: Double?,
    val devices: Map<String, HistoryDevice>,
)

data class HistoryDevice(val up: Long, val down: Long, val connections: Int)
