package com.netwatch.app.router

/**
 * Works out who each LAN IP is. The ARP table alone is not enough: a range extender in
 * repeater mode answers ARP for every device behind it with its own (proxy) MAC, so the
 * DHCP lease is trusted first for the real MAC.
 */
object DeviceResolver {

    /** [database] is `nw.sh clients`: MAC -> (name, vendor) from the persistent client list. */
    fun resolve(snap: RawSnapshot, database: Map<String, Pair<String?, String?>> = emptyMap()): List<Device> {
        val leaseByIp = snap.leases.associateBy { it.ip }
        val cacheByIp = snap.clientCache.values.filter { it.ip != null }.groupBy { it.ip!! }
        val arpShare = snap.arp.values.groupingBy { it }.eachCount()

        val ips = LinkedHashSet<String>().apply {
            addAll(snap.counters.keys)
            addAll(snap.arp.keys)
            snap.leases.forEach { add(it.ip) }
        }

        val usedKeys = HashSet<String>()
        return ips.filter { RouterParser.isIpv4(it) }.map { ip ->
            val arpMac = snap.arp[ip]
            val sharedArp = arpMac != null && (arpShare[arpMac] ?: 0) > 1
            val mac = leaseByIp[ip]?.mac
                ?: cacheByIp[ip]?.firstOrNull { it.mac != arpMac || !sharedArp }?.mac
                ?: arpMac?.takeUnless { sharedArp }
            val cached = mac?.let { snap.clientCache[it] }
            val db = mac?.let { database[it] }

            val name = listOfNotNull(
                mac?.let { snap.customNames[it] },
                cached?.nickName,
                cached?.name?.takeUnless { looksLikeMac(it) },
                db?.first?.takeUnless { looksLikeMac(it) },
                leaseByIp[ip]?.hostname,
                cached?.vendor,
                db?.second,
            ).map(::tidy).firstOrNull { it.isNotEmpty() } ?: ip

            // One MAC can hold two IPs (an extender's own uplink often does); keys must stay unique.
            val key = (mac ?: ip).let { if (usedKeys.add(it)) it else "$it/$ip".also(usedKeys::add) }
            val viaBridge = sharedArp && arpMac != mac
            Device(
                key = key,
                ip = ip,
                mac = mac,
                name = name,
                vendor = (cached?.vendor ?: db?.second)?.let(::tidy)?.ifEmpty { null },
                // Behind an extender the router only sees the extender's link, which networkmap reports as wired.
                link = if (viaBridge) Link.UNKNOWN else when (cached?.wifiBand) {
                    0 -> Link.WIRED
                    1 -> Link.WIFI_2G
                    2, 3 -> Link.WIFI_5G
                    else -> Link.UNKNOWN
                },
                rssi = if (viaBridge) null else cached?.rssi,
                viaBridge = viaBridge,
                online = arpMac != null,
            )
        }
    }

    private fun looksLikeMac(s: String) = RouterParser.isMac(s)

    private fun tidy(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
