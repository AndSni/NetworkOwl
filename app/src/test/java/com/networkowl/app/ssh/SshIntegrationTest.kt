package com.networkowl.app.ssh

import com.networkowl.app.router.DeviceResolver
import com.networkowl.app.router.RouterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Opt-in: NETWORKOWL_IT=1 runs these. They need /usr/sbin/sshd (local test) and, for the router
 * test, NETWORKOWL_ROUTER / NETWORKOWL_ROUTER_USER / NETWORKOWL_ROUTER_KEY (an authorised ed25519 key file).
 */
class SshIntegrationTest {
    private val enabled = System.getenv("NETWORKOWL_IT") == "1"

    /** The phone's code path: an in-memory ECDSA identity against a real OpenSSH server. */
    @Test
    fun ecdsaIdentityAgainstLocalSshd() {
        assumeTrue(enabled && File("/usr/sbin/sshd").exists())
        val dir = Files.createTempDirectory("nwsshd").toFile()
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = EcdsaIdentity(pair.public as ECPublicKey, pair.private)
        File(dir, "authorized_keys").writeText(EcdsaIdentity.openSshLine(pair.public as ECPublicKey, "test") + "\n")
        ProcessBuilder("ssh-keygen", "-q", "-t", "ecdsa", "-b", "256", "-N", "", "-f", File(dir, "host").path)
            .start().waitFor()
        val port = ServerSocket(0).use { it.localPort }
        val cfg = File(dir, "sshd_config").apply {
            writeText(
                """
                Port $port
                ListenAddress 127.0.0.1
                HostKey ${dir.path}/host
                AuthorizedKeysFile ${dir.path}/authorized_keys
                PidFile ${dir.path}/pid
                UsePAM no
                StrictModes no
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                PubkeyAuthentication yes
                """.trimIndent()
            )
        }
        val sshd = ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", cfg.path).redirectErrorStream(true).start()
        try {
            Thread.sleep(800)
            var pinned: String? = null
            val ssh = RouterSsh("127.0.0.1", port, System.getProperty("user.name"), identity, null) { pinned = it }
            ssh.connect()
            assertEquals("hello", ssh.exec("echo hello").trim())
            assertEquals("piped through stdin", ssh.exec("cat", stdin = "piped through stdin".toByteArray()))
            assertNotNull(pinned)
            ssh.disconnect()

            // Second connection with the pinned key succeeds; a wrong pin is refused.
            RouterSsh("127.0.0.1", port, System.getProperty("user.name"), identity, pinned) {}.apply { connect(); disconnect() }
            val wrong = RouterSsh("127.0.0.1", port, System.getProperty("user.name"), identity, "AAAA") {}
            val err = runCatching { wrong.connect() }.exceptionOrNull()
            assertTrue(generateSequence(err) { it.cause }.any { it is HostKeyChangedException })
        } finally {
            sshd.destroy()
            dir.deleteRecursively()
        }
    }

    /** The app's algorithms and owl.sh against the real router (ed25519 PC key; same everything else). */
    @Test
    fun routerSnapshot() {
        val host = System.getenv("NETWORKOWL_ROUTER")
        assumeTrue(enabled && host != null)
        val keyFile = System.getenv("NETWORKOWL_ROUTER_KEY")
        val ssh = RouterSsh(
            host = host!!,
            port = 22,
            user = System.getenv("NETWORKOWL_ROUTER_USER"),
            pinnedHostKey = null,
            onHostKeyPinned = {},
            pubkeyAlgorithms = "ssh-ed25519",
            addIdentity = { it.addIdentity(keyFile) },
        )
        val script = File("src/main/assets/owl.sh").readBytes()
        ssh.exec("mkdir -p /tmp/networkowl && cat > /tmp/networkowl/owl.sh.tmp && mv /tmp/networkowl/owl.sh.tmp /tmp/networkowl/owl.sh", stdin = script)
        val version = Regex("""OWL_VERSION=(\d+)""").find(String(script))!!.groupValues[1]
        assertEquals(version, ssh.exec("sh /tmp/networkowl/owl.sh version").trim())

        val snap = RouterParser.parseSnapshot(ssh.exec("sh /tmp/networkowl/owl.sh snapshot ping"))
        val devices = DeviceResolver.resolve(snap, RouterParser.parseClientDatabase(ssh.exec("sh /tmp/networkowl/owl.sh clients")))
        println("WAN ${snap.wanIf} rx=${snap.wanRx} probe=${snap.probe} lte=${snap.lte}")
        devices.filter { it.online }.forEach { println("  ${it.ip} ${it.mac} ${it.link} ${it.rssi} bridge=${it.viaBridge} '${it.name}'") }
        assertTrue(devices.count { it.online } > 0)
        assertNotNull(snap.lte.sinr)
        assertNotNull(snap.probe)

        val history = RouterParser.parseHistory(ssh.exec("sh /tmp/networkowl/owl.sh history"))
        println("history points: ${history.size}")
        ssh.disconnect()
    }
}
