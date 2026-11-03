package com.netwatch.app.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterParserTest {
    private val text = javaClass.getResource("/snapshot.txt")!!.readText()
    private val snap = RouterParser.parseSnapshot(text)

    @Test
    fun parsesHeaderWanAndProbe() {
        assertEquals(5, snap.scriptVersion)
        assertEquals(1793715137L, snap.epoch)
        assertEquals(43848.37, snap.uptime, 1e-9)
        assertEquals("usb0", snap.wanIf)
        assertEquals(3625500701L, snap.wanRx)
        assertEquals(937611404L, snap.wanTx)
        assertEquals(Probe(0, 87.1), snap.probe)
        assertTrue(snap.recorderRunning)
    }

    @Test
    fun parsesCountersAndConnections() {
        assertEquals(ByteCounters(up = 1919568, down = 2914153), snap.counters["192.168.50.150"])
        assertEquals(157, snap.connections["192.168.50.250"])
        assertEquals(5, snap.counters.size)
    }

    @Test
    fun parsesLte() {
        val l = snap.lte
        assertEquals(-96, l.rsrp)
        assertEquals(-14, l.rsrq)
        assertEquals(-4, l.sinr)
        assertEquals("BAND 7", l.primaryBand)
        assertEquals("BAND 8", l.secondaryBand)
        assertEquals("LV TELE2", l.provider)
        assertEquals(Grade.POOR, l.sinrGrade)
        assertEquals(Grade.FAIR, l.rsrpGrade)
    }

    @Test
    fun skipsDuidLeaseLine() {
        assertEquals(5, snap.leases.size)
        assertNull(snap.leases.first { it.ip == "192.168.50.206" }.hostname)
        assertEquals("BE:CE:48:F2:51:21", snap.leases.first { it.ip == "192.168.50.150" }.mac)
    }

    @Test
    fun decodesCustomNames() {
        assertEquals("Kitchen Tablet", snap.customNames["D8:63:0D:02:8D:0F"])
        assertEquals("Work PC", snap.customNames["FC:AA:14:9E:70:20"])
    }

    @Test
    fun parsesClientCache() {
        val mac = snap.clientCache["BE:CE:48:F2:51:21"]!!
        assertEquals(2, mac.wifiBand)
        assertEquals(-58, mac.rssi)
        // "0" means no reading for wired devices, not 0 dBm.
        assertNull(snap.clientCache["12:DB:69:0B:0D:13"]!!.rssi)
    }

    @Test
    fun parsesHistory() {
        val points = RouterParser.parseHistory(
            "1793715274|100|50|-97|-15|-4|BAND 7|0|90.7|192.168.50.150:10:20:3,192.168.50.135:1:2:0\n" +
                "garbage line\n" +
                "1793715244|90|40|-96|-14|-3|BAND 7|33|-1|192.168.50.150:5:10:2\n"
        )
        assertEquals(2, points.size)
        assertEquals(1793715244L, points[0].epoch)
        assertNull(points[0].avgMs)
        assertEquals(33, points[0].lossPercent)
        assertEquals(HistoryDevice(10, 20, 3), points[1].devices["192.168.50.150"])
    }

    @Test
    fun parsesClientDatabase() {
        val db = RouterParser.parseClientDatabase(
            """K {"A8:42:A1:64:48:56":{"mac":"A8:42:A1:64:48:56","name":"RE315","vendor":"TP-Link"}}"""
        )
        assertEquals("RE315" to "TP-Link", db["A8:42:A1:64:48:56"])
        assertFalse(db.containsKey("x"))
    }
}
