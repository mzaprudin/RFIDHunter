package ru.rfidhunter.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Non-exportable per-install ECDSA identity stored by Android Keystore. */
class DeviceIdentity(private val deviceId: String) {
    private val alias = "rfidhunter.device.$deviceId"

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun keyPair(): KeyPair {
        val store = keyStore()
        val existing = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(keyPair().public.encoded)

    fun keyId(): String = sha256(keyPair().public.encoded).take(32)

    fun sign(data: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair().private)
        signature.update(data)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
