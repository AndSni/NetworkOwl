package com.netwatch.app.monitor

import com.netwatch.app.router.ByteCounters
import com.netwatch.app.router.DeviceResolver
import com.netwatch.app.router.HistoryDevice
import com.netwatch.app.router.HistoryPoint
import com.netwatch.app.router.RouterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorEngineTest {
    private val base = RouterParser.parseSnapshot(javaClass.getResource("/snapshot.txt")!!.readText())

    @Test
    fun ratesFromConsecutiveSnapshots() {
        val engine = MonitorEngine()
        val devices = DeviceResolver.resolve(base)
        assertNull(engine.update(base, devices, 3))

        val next = base.copy(
            epoch = base.epoch + 2,
            uptime = base.uptime + 2.0,
            wanRx = base.wanRx + 1_000_000,
            wanTx = base.wanTx + 200_000,
            counters = base.counters + ("192.168.50.150" to ByteCounters(up = 1919568 + 100_000, down = 2914153 + 800_000)),
        )
        val frame = engine.update(next, devices, 3)!!
        val mac = frame.devices.first { it.device.ip == "192.168.50.150" }
        assertEquals(400_000.0, mac.downBps, 1e-6)
        assertEquals(50_000.0, mac.upBps, 1e-6)
        assertEquals(800_000L, mac.sessionDown)
        // Heaviest device sorts first.
        assertEquals("192.168.50.150", frame.devices.first().device.ip)
        // 1 MB arrived on the WAN, 0.8 MB went to the Mac: 0.2 MB is the router itself/other.
        assertEquals(100_000.0, frame.otherDownBps, 1e-6)
        assertEquals(500_000.0, frame.wanDownBps, 1e-6)
    }

    @Test
    fun ruleRecreatedCountsFromZero() {
        assertEquals(500L, MonitorEngine.delta(500, 2_000_000))
    }

    @Test
    fun wanCounterWrapsAt32Bits() {
        val old = 4_294_000_000L
        val cur = 1_000_000L
        assertEquals(cur + (1L shl 32) - old, MonitorEngine.interfaceDelta(cur, old))
    }

    @Test
    fun routerRebootGivesNoFrame() {
        val engine = MonitorEngine()
        val devices = DeviceResolver.resolve(base)
        engine.update(base, devices, null)
        assertNull(engine.update(base.copy(uptime = 30.0), devices, null))
    }

    @Test
    fun historyIntervalsSkipGapsAndFlagSlow() {
        fun p(t: Long, rx: Long, ms: Double?, loss: Int, dev: Long) = HistoryPoint(
            t, rx, 0, -96, -14, -4, "BAND 7", loss, ms, mapOf("192.168.50.150" to HistoryDevice(0, dev, 5)),
        )
        val intervals = HistoryAnalyzer.intervals(
            listOf(
                p(1000, 0, 80.0, 0, 0),
                p(1030, 3_000_000, 200.0, 0, 2_000_000),
                p(1060, 3_300_000, 60.0, 0, 2_100_000),
                p(2000, 3_400_000, 60.0, 33, 2_200_000), // 940 s gap: not bridged
            )
        )
        assertEquals(2, intervals.size)
        assertEquals(100_000.0, intervals[0].wanDownBps, 1e-6)
        assertTrue(intervals[0].slow)
        assertFalse(intervals[1].slow)
        val totals = HistoryAnalyzer.totals(intervals)
        assertEquals(2_100_000L, totals.single().second.down)
        assertNotNull(totals)
    }
}
