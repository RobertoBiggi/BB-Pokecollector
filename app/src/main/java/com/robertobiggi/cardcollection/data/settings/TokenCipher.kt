package com.robertobiggi.cardcollection.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts a secret (the CardTrader token) with AES-GCM: random IV at every encryption and an
 * integrity check, so a tampered value does not decrypt. The saved text is "v1.<IV>.<ciphertext>" in Base64 URL.
 * The key comes from outside: on the phone from the Android Keystore ([KeystoreKeys]), in tests from a software key.
 *
 * @param key () -> SecretKey - AES key to use; called at every operation (the Keystore key never leaves the device).
 * @param context String - authenticated associated data (AAD): a value encrypted for one use does not decrypt for another.
 */
class TokenCipher(private val key: () -> SecretKey, private val context: String = DEFAULT_CONTEXT) {

    /**
     * Encrypts a secret.
     * @param plain String - plain-text secret, not empty.
     * @return String - text to save ("v1.<IV>.<ciphertext>").
     * @throws GeneralSecurityException if the Keystore is not available or encryption fails.
     */
    fun encrypt(plain: String): String {
        require(plain.isNotEmpty()) { "Segreto vuoto" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The IV is chosen by the provider (the Keystore enforces it): random at every encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return listOf(VERSION, B64.encodeToString(cipher.iv), B64.encodeToString(sealed)).joinToString(SEPARATOR)
    }

    /**
     * Decrypts a secret saved with [encrypt].
     * @param stored String - saved text.
     * @return String? - plain-text secret, or null if the text is malformed, tampered with or the key is no longer the same
     *         (e.g. data restored on another phone, or deleted key): the user will have to enter the secret again.
     */
    fun decrypt(stored: String): String? {
        if (stored.length > MAX_STORED_LENGTH) return null
        val parts = stored.split(SEPARATOR)
        if (parts.size != 3 || parts[0] != VERSION) return null
        return try {
            val iv = B64_DECODER.decode(parts[1])
            val sealed = B64_DECODER.decode(parts[2])
            if (iv.size != IV_BYTES || sealed.size <= TAG_BITS / 8) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // Invalid Base64.
            null
        }
    }

    companion object {
        /** Context (AAD) of the CardTrader token. */
        const val DEFAULT_CONTEXT = "cardtrader_token"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = "v1"
        private const val SEPARATOR = "."
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12

        /** Maximum length of the saved text accepted (a CardTrader token is a few hundred characters long). */
        private const val MAX_STORED_LENGTH = 8_192

        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64_DECODER = Base64.getUrlDecoder()
    }
}

/** Android Keystore keys used by the app: generated on the phone, not exportable, not included in any backup. */
object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    /** Alias of the key that encrypts the CardTrader token. */
    private const val CARDTRADER_ALIAS = "cardtrader_token_key"

    /**
     * AES-256 key for the CardTrader token: created on first use, then read again from the Keystore. It does not require
     * unlocking the phone, because the price check also runs in the background (e.g. at 4:30).
     * @return SecretKey - reference to the key in the Keystore (the key material never leaves the Keystore).
     * @throws GeneralSecurityException if the Keystore is not available.
     */
    fun cardTraderKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(CARDTRADER_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(CARDTRADER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
