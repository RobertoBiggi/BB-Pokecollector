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
 * Cifra e decifra un segreto (il token di CardTrader) con AES-GCM: IV casuale a ogni cifratura e controllo di
 * integrità, così un valore manomesso non si decifra. Il testo salvato è "v1.<IV>.<testo cifrato>" in Base64 URL.
 * La chiave arriva da fuori: sul telefono dall'Android Keystore ([KeystoreKeys]), nei test da una chiave software.
 *
 * @param key () -> SecretKey - chiave AES da usare; chiamata a ogni operazione (la chiave del Keystore non lascia mai il dispositivo).
 * @param context String - dato associato autenticato (AAD): un valore cifrato per un uso non si decifra per un altro.
 */
class TokenCipher(private val key: () -> SecretKey, private val context: String = DEFAULT_CONTEXT) {

    /**
     * Cifra un segreto.
     * @param plain String - segreto in chiaro, non vuoto.
     * @return String - testo da salvare ("v1.<IV>.<testo cifrato>").
     * @throws GeneralSecurityException se il Keystore non è disponibile o la cifratura fallisce.
     */
    fun encrypt(plain: String): String {
        require(plain.isNotEmpty()) { "Segreto vuoto" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Lo IV lo sceglie il provider (il Keystore lo impone): casuale a ogni cifratura.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return listOf(VERSION, B64.encodeToString(cipher.iv), B64.encodeToString(sealed)).joinToString(SEPARATOR)
    }

    /**
     * Decifra un segreto salvato con [encrypt].
     * @param stored String - testo salvato.
     * @return String? - segreto in chiaro, oppure null se il testo è malformato, manomesso o la chiave non è più quella
     *         (es. dati ripristinati su un altro telefono, o chiave cancellata): l'utente dovrà reinserire il segreto.
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
            // Base64 non valido.
            null
        }
    }

    companion object {
        /** Contesto (AAD) del token di CardTrader. */
        const val DEFAULT_CONTEXT = "cardtrader_token"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = "v1"
        private const val SEPARATOR = "."
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12

        /** Lunghezza massima del testo salvato accettata (un token CardTrader è lungo qualche centinaio di caratteri). */
        private const val MAX_STORED_LENGTH = 8_192

        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64_DECODER = Base64.getUrlDecoder()
    }
}

/** Chiavi dell'Android Keystore usate dall'app: generate sul telefono, non esportabili, non incluse in nessun backup. */
object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    /** Alias della chiave che cifra il token di CardTrader. */
    private const val CARDTRADER_ALIAS = "cardtrader_token_key"

    /**
     * Chiave AES-256 per il token di CardTrader: la crea al primo uso, poi la rilegge dal Keystore. Non richiede lo
     * sblocco del telefono, perché il controllo prezzi gira anche in background (es. alle 4:30).
     * @return SecretKey - riferimento alla chiave nel Keystore (il materiale della chiave non esce mai dal Keystore).
     * @throws GeneralSecurityException se il Keystore non è disponibile.
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
