package com.damfizz.myvault.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.damfizz.myvault.core.LocalSecretBox
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val PROVIDER = "AndroidKeyStore"

private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

/**
 * Seals device-local secrets (e.g. the Secret Key) with a non-exportable Android Keystore key, so
 * a copy of the app's files (backup, adb, another phone) cannot reveal them. No user auth needed.
 */
object KeystoreSecretBox : LocalSecretBox {
    private const val ALIAS = "myvault-local-box"

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/**
 * Biometric quick unlock: an AES key that the Keystore only releases after a STRONG biometric check,
 * invalidated when fingerprints/faces change. It wraps the raw vault key for this phone only.
 * The Authenticator (TOTP) is still enforced after a biometric unlock, unless the device is trusted.
 */
object BiometricKey {
    private const val ALIAS = "myvault-biometric"

    class Invalidated : Exception("Biometric quick unlock was reset because your fingerprints or face changed.")

    private fun createKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        gen.init(b.build())
        return gen.generateKey()
    }

    /** Cipher for wrapping the vault key; always a fresh key (re-enabling replaces the old wrap). */
    fun encryptCipher(): Cipher {
        delete()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, createKey()) }
    }

    fun decryptCipher(iv: ByteArray): Cipher {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw Invalidated()
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            delete()
            throw Invalidated()
        }
    }

    fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}
