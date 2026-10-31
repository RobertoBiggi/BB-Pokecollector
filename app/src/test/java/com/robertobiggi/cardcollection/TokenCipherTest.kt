package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.data.settings.TokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * CardTrader token encryption: same code as the app, with a software AES key instead of the Android
 * Keystore one (which does not exist on the JVM).
 */
class TokenCipherTest {

    /**
     * Software AES-256 key, like the one the Keystore creates on the phone.
     * @return SecretKey - new key.
     */
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = TokenCipher({ key })
    private val token = "eyJhbGciOiJSUzI1NiJ9.esempio-di-token-cardtrader.firma"

    @Test
    fun `il token cifrato si decifra uguale e non contiene il testo in chiaro`() {
        val stored = cipher.encrypt(token)
        assertTrue(stored.startsWith("v1."))
        assertFalse(stored.contains("cardtrader"))
        assertEquals(token, cipher.decrypt(stored))
    }

    @Test
    fun `ogni cifratura usa un IV nuovo`() {
        assertNotEquals(cipher.encrypt(token), cipher.encrypt(token))
    }

    @Test
    fun `un valore manomesso non si decifra`() {
        val parts = cipher.encrypt(token).split(".")
        val body = parts[2].toCharArray().also { it[3] = if (it[3] == 'A') 'B' else 'A' }.concatToString()
        assertNull(cipher.decrypt(listOf(parts[0], parts[1], body).joinToString(".")))
    }

    @Test
    fun `con un'altra chiave non si decifra, come dopo un ripristino su un altro telefono`() {
        val stored = cipher.encrypt(token)
        assertNull(TokenCipher({ newKey() }).decrypt(stored))
    }

    @Test
    fun `un valore cifrato per un altro uso non vale come token`() {
        val other = TokenCipher({ key }, context = "altro_segreto").encrypt(token)
        assertNull(cipher.decrypt(other))
    }

    @Test
    fun `testi malformati non si decifrano`() {
        assertNull(cipher.decrypt(""))
        assertNull(cipher.decrypt(token))
        assertNull(cipher.decrypt("v2.AAAA.BBBB"))
        assertNull(cipher.decrypt("v1.%%%.###"))
        assertNull(cipher.decrypt("v1.AAAA.BBBB"))
        assertNull(cipher.decrypt("v1." + "A".repeat(10_000) + ".B"))
    }
}
