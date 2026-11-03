package com.networkowl.app.ssh

import com.jcraft.jsch.Identity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * A JSch identity for an ecdsa-sha2-nistp256 key whose private half is only reachable
 * through a JCA [PrivateKey] handle. On the phone that handle points into the Android
 * Keystore, so the private key never exists in app memory or storage.
 */
class EcdsaIdentity(
    private val publicKey: ECPublicKey,
    private val privateKey: PrivateKey,
    private val label: String = "networkowl",
) : Identity {

    override fun getPublicKeyBlob(): ByteArray = publicBlob(publicKey)

    override fun getSignature(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return sshSignature(der)
    }

    override fun getSignature(data: ByteArray, alg: String): ByteArray = getSignature(data)
    override fun getAlgName(): String = ALG
    override fun getName(): String = label
    override fun setPassphrase(passphrase: ByteArray?): Boolean = true
    override fun isEncrypted(): Boolean = false
    override fun clear() {}

    companion object {
        const val ALG = "ecdsa-sha2-nistp256"
        private const val CURVE = "nistp256"

        fun publicBlob(key: ECPublicKey): ByteArray = wire {
            string(ALG.toByteArray())
            string(CURVE.toByteArray())
            string(point(key))
        }

        /** The line to paste into the router's Authorized Keys box. */
        fun openSshLine(key: ECPublicKey, comment: String): String =
            "$ALG ${Base64.getEncoder().encodeToString(publicBlob(key))} $comment"

        fun fingerprint(blob: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

        /** Uncompressed SEC1 point: 0x04 || X || Y, each coordinate padded to 32 bytes. */
        private fun point(key: ECPublicKey): ByteArray {
            val x = fixed(key.w.affineX)
            val y = fixed(key.w.affineY)
            return byteArrayOf(4) + x + y
        }

        private fun fixed(n: BigInteger): ByteArray {
            val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }

        /** JCA gives DER SEQUENCE{r, s}; SSH wants string(alg) + string(mpint r + mpint s). */
        internal fun sshSignature(der: ByteArray): ByteArray {
            val (r, s) = parseDer(der)
            val body = wire {
                mpint(r)
                mpint(s)
            }
            return wire {
                string(ALG.toByteArray())
                string(body)
            }
        }

        internal fun parseDer(der: ByteArray): Pair<BigInteger, BigInteger> {
            var i = 0
            fun length(): Int {
                val first = der[i++].toInt() and 0xff
                if (first < 0x80) return first
                var len = 0
                repeat(first and 0x7f) { len = (len shl 8) or (der[i++].toInt() and 0xff) }
                return len
            }
            fun integer(): BigInteger {
                require(der[i++] == 0x02.toByte()) { "expected INTEGER" }
                val len = length()
                return BigInteger(der.copyOfRange(i, i + len)).also { i += len }
            }
            require(der[i++] == 0x30.toByte()) { "expected SEQUENCE" }
            length()
            return integer() to integer()
        }

        private fun wire(block: Wire.() -> Unit): ByteArray =
            ByteArrayOutputStream().also { Wire(DataOutputStream(it)).block() }.toByteArray()

        private class Wire(val out: DataOutputStream) {
            fun string(b: ByteArray) {
                out.writeInt(b.size)
                out.write(b)
            }
            fun mpint(n: BigInteger) = string(n.toByteArray())
        }
    }
}
