package com.networkowl.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class HostKeyChangedException(val expected: String, val actual: String) :
    Exception("The router's SSH host key changed. Expected $expected, got $actual.")

/**
 * One SSH connection to the router, reused for every command. Algorithms are pinned to
 * ones Android's built-in crypto provides and the stock ASUS dropbear accepts, so no extra
 * crypto library is needed.
 *
 * The host key is trust-on-first-use: [pinnedHostKey] is the base64 key seen last time
 * (null on first connect), and [onHostKeyPinned] receives the key to store.
 */
class RouterSsh(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pinnedHostKey: String?,
    private val onHostKeyPinned: (String) -> Unit,
    private val pubkeyAlgorithms: String = EcdsaIdentity.ALG,
    private val addIdentity: (JSch) -> Unit,
) {
    constructor(
        host: String,
        port: Int,
        user: String,
        identity: Identity,
        pinnedHostKey: String?,
        onHostKeyPinned: (String) -> Unit,
    ) : this(host, port, user, pinnedHostKey, onHostKeyPinned, EcdsaIdentity.ALG, { it.addIdentity(identity, null) })

    private var session: Session? = null

    val isConnected: Boolean get() = session?.isConnected == true

    @Synchronized
    fun connect() {
        if (isConnected) return
        val jsch = JSch()
        addIdentity(jsch)
        jsch.hostKeyRepository = PinnedHostKeys()
        session = jsch.getSession(user, host, port).apply {
            setConfig("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,diffie-hellman-group14-sha256")
            setConfig("server_host_key", "ecdsa-sha2-nistp256,rsa-sha2-256")
            setConfig("cipher.s2c", "aes128-ctr,aes256-ctr")
            setConfig("cipher.c2s", "aes128-ctr,aes256-ctr")
            setConfig("mac.s2c", "hmac-sha2-256")
            setConfig("mac.c2s", "hmac-sha2-256")
            setConfig("PubkeyAcceptedAlgorithms", pubkeyAlgorithms)
            setConfig("PreferredAuthentications", "publickey")
            setConfig("StrictHostKeyChecking", if (pinnedHostKey == null) "no" else "yes")
            userInfo = SilentUserInfo
            setServerAliveInterval(15_000)
            connect(8_000)
        }
    }

    @Synchronized
    fun disconnect() {
        session?.disconnect()
        session = null
    }

    /** Runs [command] and returns stdout. Reconnects once if the session dropped. */
    fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long = 20_000): String {
        if (!isConnected) connect()
        return try {
            run(command, stdin, timeoutMs)
        } catch (e: Exception) {
            if (e is HostKeyChangedException) throw e
            disconnect()
            connect()
            run(command, stdin, timeoutMs)
        }
    }

    private fun run(command: String, stdin: ByteArray?, timeoutMs: Long): String {
        val channel = session!!.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        if (stdin != null) channel.setInputStream(ByteArrayInputStream(stdin)) else channel.setInputStream(null)
        val err = ByteArrayOutputStream()
        channel.setErrStream(err)
        val out = ByteArrayOutputStream()
        val input = channel.inputStream
        channel.connect(5_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                while (input.available() > 0) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (channel.isClosed) {
                    if (input.available() > 0) continue
                    break
                }
                if (System.currentTimeMillis() > deadline) error("Router command timed out: $command")
                Thread.sleep(20)
            }
        } finally {
            channel.disconnect()
        }
        if (channel.exitStatus != 0 && out.size() == 0) {
            error("Router command failed (${channel.exitStatus}): ${err.toString().trim()}")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private inner class PinnedHostKeys : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val seen = Base64.getEncoder().encodeToString(key)
            return when (pinnedHostKey) {
                null -> HostKeyRepository.NOT_INCLUDED
                seen -> HostKeyRepository.OK
                else -> throw HostKeyChangedException(
                    EcdsaIdentity.fingerprint(Base64.getDecoder().decode(pinnedHostKey)),
                    EcdsaIdentity.fingerprint(key),
                )
            }
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) = onHostKeyPinned(hostkey.key)
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "networkowl"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private object SilentUserInfo : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
    }
}
