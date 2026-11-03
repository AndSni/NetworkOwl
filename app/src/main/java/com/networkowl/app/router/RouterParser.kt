package com.networkowl.app.router

import org.json.JSONObject

/** Parses the line-oriented output of owl.sh. Unknown or malformed lines are skipped. */
object RouterParser {

    fun parseSnapshot(text: String): RawSnapshot {
        var version = 0
        var epoch = 0L
        var uptime = 0.0
        var wanIf: String? = null
        var wanRx = 0L
        var wanTx = 0L
        val counters = HashMap<String, ByteCounters>()
        val connections = HashMap<String, Int>()
        val lte = HashMap<String, String>()
        val arp = HashMap<String, String>()
        val leases = ArrayList<Lease>()
        var customNames: Map<String, String> = emptyMap()
        var cache: Map<String, CachedClient> = emptyMap()
        var recorder = false
        var probe: Probe? = null

        for (line in text.lineSequence()) {
            if (line.length < 2 || line[1] != ' ') continue
            val rest = line.substring(2)
            val f = rest.split(' ')
            when (line[0]) {
                'V' -> version = f[0].toIntOrNull() ?: 0
                'T' -> {
                    epoch = f.getOrNull(0)?.toLongOrNull() ?: 0
                    uptime = f.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                }
                'W' -> if (f.size >= 3) {
                    wanIf = f[0]
                    wanRx = f[1].toLongOrNull() ?: 0
                    wanTx = f[2].toLongOrNull() ?: 0
                }
                'D' -> if (f.size >= 3) {
                    counters[f[0]] = ByteCounters(f[1].toLongOrNull() ?: 0, f[2].toLongOrNull() ?: 0)
                }
                'C' -> if (f.size >= 2) f[1].toIntOrNull()?.let { connections[f[0]] = it }
                'L' -> {
                    val eq = rest.indexOf('=')
                    if (eq > 0) lte[rest.substring(0, eq)] = rest.substring(eq + 1).trim().trim('"')
                }
                'A' -> if (f.size >= 2 && isIpv4(f[0])) arp[f[0]] = normalizeMac(f[1])
                'H' -> if (f.size >= 2 && isIpv4(f[1]) && isMac(f[0])) {
                    val host = f.getOrNull(2)?.takeUnless { it.isBlank() || it == "*" }
                    leases += Lease(normalizeMac(f[0]), f[1], host)
                }
                'N' -> customNames = parseCustomClientList(rest)
                'J' -> cache = parseClientCache(rest)
                'R' -> recorder = f[0] == "1"
                'P' -> if (f.size >= 2) {
                    val avg = f[1].toDoubleOrNull()?.takeIf { it >= 0 }
                    probe = Probe(f[0].toDoubleOrNull()?.toInt() ?: 100, avg)
                }
            }
        }

        return RawSnapshot(
            scriptVersion = version,
            epoch = epoch,
            uptime = uptime,
            wanIf = wanIf,
            wanRx = wanRx,
            wanTx = wanTx,
            counters = counters,
            connections = connections,
            lte = LteInfo(
                rsrp = lte["rsrp"]?.toIntOrNull(),
                rsrq = lte["rsrq"]?.toIntOrNull(),
                sinr = lte["sinr"]?.toIntOrNull(),
                rssi = lte["rssi"]?.toIntOrNull(),
                bars = lte["signal"]?.toIntOrNull(),
                band = lte["band"]?.ifBlank { null },
                primaryBand = lte["pcc"]?.ifBlank { null },
                secondaryBand = lte["scc"]?.ifBlank { null },
                technology = lte["operation"]?.ifBlank { null },
                provider = lte["provider"]?.ifBlank { null },
                cellId = lte["cellid"]?.ifBlank { null },
            ),
            arp = arp,
            leases = leases,
            customNames = customNames,
            clientCache = cache,
            recorderRunning = recorder,
            probe = probe,
        )
    }

    /**
     * nvram custom_clientlist: `<name>MAC>…<name>MAC>…`. Names are URL-encoded by the web UI.
     */
    fun parseCustomClientList(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (entry in raw.split('<')) {
            val parts = entry.split('>')
            if (parts.size < 2 || !isMac(parts[1])) continue
            val name = percentDecode(parts[0]).trim()
            if (name.isNotEmpty()) out[normalizeMac(parts[1])] = name
        }
        return out
    }

    /** /tmp/nmp_cache.js: networkmap's live client table, keyed by MAC. */
    fun parseClientCache(json: String): Map<String, CachedClient> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, CachedClient>()
        for (key in root.keys()) {
            val o = root.optJSONObject(key) ?: continue
            if (!isMac(key)) continue
            val mac = normalizeMac(key)
            out[mac] = CachedClient(
                mac = mac,
                ip = o.optString("ip").takeIf { isIpv4(it) },
                name = o.optString("name").ifBlank { null },
                nickName = o.optString("nickName").ifBlank { null },
                vendor = o.optString("vendor").ifBlank { null },
                wifiBand = o.optString("isWL").toIntOrNull(),
                rssi = o.optString("rssi").toIntOrNull()?.takeIf { it < 0 },
                online = o.optString("isOnline") == "1",
            )
        }
        return out
    }

    /** `owl.sh clients`: the persistent client database (/jffs/nmp_cl_json.js). MAC -> (name, vendor). */
    fun parseClientDatabase(text: String): Map<String, Pair<String?, String?>> {
        val line = text.lineSequence().firstOrNull { it.startsWith("K ") } ?: return emptyMap()
        val root = runCatching { JSONObject(line.substring(2)) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Pair<String?, String?>>()
        for (key in root.keys()) {
            val o = root.optJSONObject(key) ?: continue
            if (!isMac(key)) continue
            out[normalizeMac(key)] = o.optString("name").ifBlank { null } to o.optString("vendor").ifBlank { null }
        }
        return out
    }

    /** `owl.sh history`: ts|rx|tx|rsrp|rsrq|sinr|band|loss|avg|ip:up:down:conns,... */
    fun parseHistory(text: String): List<HistoryPoint> = text.lineSequence().mapNotNull { line ->
        val f = line.split('|')
        if (f.size < 10) return@mapNotNull null
        val epoch = f[0].toLongOrNull() ?: return@mapNotNull null
        val devices = HashMap<String, HistoryDevice>()
        for (d in f[9].split(',')) {
            val p = d.split(':')
            if (p.size < 4 || !isIpv4(p[0])) continue
            devices[p[0]] = HistoryDevice(p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3].toIntOrNull() ?: 0)
        }
        HistoryPoint(
            epoch = epoch,
            wanRx = f[1].toLongOrNull() ?: 0,
            wanTx = f[2].toLongOrNull() ?: 0,
            rsrp = f[3].toIntOrNull(),
            rsrq = f[4].toIntOrNull(),
            sinr = f[5].toIntOrNull(),
            band = f[6].ifBlank { null },
            lossPercent = f[7].toDoubleOrNull()?.toInt(),
            avgMs = f[8].toDoubleOrNull()?.takeIf { it >= 0 },
            devices = devices,
        )
    }.sortedBy { it.epoch }.toList()

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val MAC = Regex("""[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}""")

    fun isIpv4(s: String) = IPV4.matches(s)
    fun isMac(s: String) = MAC.matches(s)
    fun normalizeMac(s: String) = s.uppercase()

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray())
            i++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
