package com.networkowl.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class EcdsaIdentityTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = pair.public as ECPublicKey
    private val identity = EcdsaIdentity(pub, pair.private)

    private fun DataInputStream.string(): ByteArray = ByteArray(readInt()).also { readFully(it) }

    @Test
    fun publicBlobLayout() {
        val blob = DataInputStream(ByteArrayInputStream(identity.publicKeyBlob))
        assertEquals("ecdsa-sha2-nistp256", String(blob.string()))
        assertEquals("nistp256", String(blob.string()))
        val q = blob.string()
        assertEquals(65, q.size)
        assertEquals(4.toByte(), q[0])
        assertEquals(pub.w.affineX, BigInteger(1, q.copyOfRange(1, 33)))
        assertEquals(pub.w.affineY, BigInteger(1, q.copyOfRange(33, 65)))
    }

    @Test
    fun signatureVerifiesAfterConvertingBackToDer() {
        val data = "session-id and userauth request".toByteArray()
        repeat(20) { // r and s vary in length (leading zero bytes); exercise both cases
            val sig = DataInputStream(ByteArrayInputStream(identity.getSignature(data, "ecdsa-sha2-nistp256")))
            assertEquals("ecdsa-sha2-nistp256", String(sig.string()))
            val body = DataInputStream(ByteArrayInputStream(sig.string()))
            val r = BigInteger(body.string())
            val s = BigInteger(body.string())
            assertTrue(r.signum() > 0 && s.signum() > 0)
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(data)
                verify(der(r, s))
            }
            assertTrue(ok)
        }
    }

    @Test
    fun openSshLine() {
        val line = EcdsaIdentity.openSshLine(pub, "networkowl-phone")
        val parts = line.split(' ')
        assertEquals("ecdsa-sha2-nistp256", parts[0])
        assertTrue(Base64.getDecoder().decode(parts[1]).contentEquals(identity.publicKeyBlob))
        assertEquals("networkowl-phone", parts[2])
    }

    private fun der(r: BigInteger, s: BigInteger): ByteArray {
        fun int(n: BigInteger) = n.toByteArray().let { byteArrayOf(2, it.size.toByte()) + it }
        val body = int(r) + int(s)
        return ByteArrayOutputStream().apply { write(0x30); write(body.size); write(body) }.toByteArray()
    }
}
