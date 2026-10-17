package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.validation.Validators
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidatorsTest {

    @Test
    fun `ricerche valide e non valide`() {
        assertTrue(Validators.isValidQueryPattern("Mawile"))
        assertTrue(Validators.isValidQueryPattern("クチート"))
        assertTrue(Validators.isValidQueryPattern("Mr. Mime"))
        assertFalse(Validators.isValidQueryPattern("a"))
        assertFalse(Validators.isValidQueryPattern("x/../y"))
        assertFalse(Validators.isValidQueryPattern("<script>"))
        assertFalse(Validators.isValidQueryPattern("a".repeat(41)))
    }

    @Test
    fun `id remoti rifiutano path traversal`() {
        assertTrue(Validators.isValidRemoteId("me02.5-144"))
        assertTrue(Validators.isValidRemoteId("SM10a"))
        assertFalse(Validators.isValidRemoteId("../etc"))
        assertFalse(Validators.isValidRemoteId("a/b"))
        assertFalse(Validators.isValidRemoteId("a..b"))
        assertFalse(Validators.isValidRemoteId(""))
    }

    @Test
    fun `nomi file sicuri`() {
        assertEquals("en_me02_5-144", Validators.safeFileSegment("en:me02.5-144"))
        assertEquals("______etc_passwd", Validators.safeFileSegment("../../etc/passwd"))
        assertEquals("_", Validators.safeFileSegment(""))
    }

    @Test
    fun `prezzi manuali`() {
        assertEquals(2.5, Validators.parseManualPrice("2,50"))
        assertEquals(10.0, Validators.parseManualPrice("10"))
        assertNull(Validators.parseManualPrice("0"))
        assertNull(Validators.parseManualPrice("-1"))
        assertNull(Validators.parseManualPrice("1.234"))
        assertNull(Validators.parseManualPrice("abc"))
    }

    @Test
    fun `url immagini solo da host consentiti in https`() {
        assertTrue(Validators.isAllowedImageUrl("https://assets.tcgdex.net/en/me/me03/032"))
        assertFalse(Validators.isAllowedImageUrl("http://assets.tcgdex.net/en/me/me03/032"))
        assertFalse(Validators.isAllowedImageUrl("https://evil.example/x.png"))
        assertFalse(Validators.isAllowedImageUrl("non un url"))
    }

    @Test
    fun `id variante`() {
        val id = Ids.variantId("en:me03-032", VariantType.REVERSE)
        assertEquals("en:me03-032|REVERSE", id)
        assertTrue(Ids.isValidVariantId(id))
        assertFalse(Ids.isValidVariantId("en:me03-032|SHINY"))
        assertFalse(Ids.isValidVariantId("xx:me03-032|HOLO"))
        assertFalse(Ids.isValidVariantId("en:../x|HOLO"))
    }
}
