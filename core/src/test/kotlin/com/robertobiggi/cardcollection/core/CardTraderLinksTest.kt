package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderLinks
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class CardTraderLinksTest {
    @Test
    fun `il link di una carta ha id e nome`() {
        assertEquals("https://www.cardtrader.com/it-IT/cards/140680-omastar", CardTraderLinks.card(140680, "Omastar"))
        assertEquals("https://www.cardtrader.com/it-IT/cards/345910-omastar-v", CardTraderLinks.card(345910, "Omastar V"))
    }

    @Test
    fun `un nome senza lettere latine lascia solo l'id`() {
        assertEquals("https://www.cardtrader.com/it-IT/cards/9", CardTraderLinks.card(9, "オムスター"))
    }
}
