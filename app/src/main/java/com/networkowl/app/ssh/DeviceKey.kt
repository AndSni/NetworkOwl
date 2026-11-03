package com.networkowl.app.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The phone's SSH key: ECDSA P-256 inside the Android Keystore. The private key can sign
 * but can't be exported, so nothing on disk or in a backup can log in to the router.
 */
object DeviceKey {
    private const val ALIAS = "networkowl_router_ssh"
    private const val STORE = "AndroidKeyStore"

    private fun keyStore() = KeyStore.getInstance(STORE).apply { load(null) }

    fun exists(): Boolean = keyStore().containsAlias(ALIAS)

    fun getOrCreate(): Pair<ECPublicKey, PrivateKey> {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).run {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                generateKeyPair()
            }
        }
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey as ECPublicKey to entry.privateKey
    }

    fun identity(): EcdsaIdentity {
        val (pub, priv) = getOrCreate()
        return EcdsaIdentity(pub, priv)
    }

    fun publicLine(): String = EcdsaIdentity.openSshLine(getOrCreate().first, "networkowl-phone")

    fun delete() = keyStore().deleteEntry(ALIAS)
}
