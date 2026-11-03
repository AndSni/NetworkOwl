package com.networkowl.app.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceResolverTest {
    private val snap = RouterParser.parseSnapshot(javaClass.getResource("/snapshot.txt")!!.readText())
    private val devices = DeviceResolver.resolve(snap).associateBy { it.ip }

    @Test
    fun extenderClientsKeepTheirOwnMacFromDhcp() {
        // ARP shows the extender's proxy MAC for .135/.206/.210; the lease has the real one.
        val phone = devices.getValue("192.168.50.135")
        assertEquals("12:DB:69:0B:0D:13", phone.mac)
        assertTrue(phone.viaBridge)
        assertTrue(phone.online)
    }

    @Test
    fun namepriority() {
        assertEquals("Kitchen Tablet", devices.getValue("192.168.50.206").name) // custom name in router UI
        assertEquals("Phone A", devices.getValue("192.168.50.135").name)        // networkmap nickname
        assertEquals("Mac", devices.getValue("192.168.50.150").name)            // networkmap name
        assertEquals("RE315", devices.getValue("192.168.50.210").name)          // DHCP host name
        assertEquals("Work PC", devices.getValue("192.168.50.250").name)
    }

    @Test
    fun linkFromNetworkmap() {
        assertEquals(Link.WIFI_5G, devices.getValue("192.168.50.150").link)
        assertEquals(-58, devices.getValue("192.168.50.150").rssi)
        assertFalse(devices.getValue("192.168.50.150").viaBridge)
    }

    @Test
    fun extenderClientsAreNotReportedAsWired() {
        val phone = devices.getValue("192.168.50.135") // networkmap says isWL 0
        assertEquals(Link.UNKNOWN, phone.link)
    }

    @Test
    fun sameMacOnTwoIpsGetsUniqueKeys() {
        val dup = snap.copy(
            arp = snap.arp + ("192.168.50.145" to "AA:42:A1:04:48:56") + ("192.168.50.209" to "AA:42:A1:04:48:57"),
            leases = snap.leases,
        )
        val twice = snap.copy(
            leases = snap.leases + Lease("BE:CE:48:F2:51:21", "192.168.50.151", null),
        )
        val keys = DeviceResolver.resolve(twice).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(DeviceResolver.resolve(dup).size, DeviceResolver.resolve(dup).map { it.key }.toSet().size)
    }

    @Test
    fun dbNameUsedWhenNothingBetter() {
        val withDb = DeviceResolver.resolve(snap, mapOf("D8:63:0D:02:8D:0F" to ("Tablet" to "Lenovo")))
        // The custom name still wins; the vendor comes through from the database.
        val tablet = withDb.first { it.ip == "192.168.50.206" }
        assertEquals("Kitchen Tablet", tablet.name)
        assertEquals("Lenovo", tablet.vendor)
    }
}
