package com.example.offerteamazon

import com.example.offerteamazon.util.PriceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceParserTest {

    private fun values(text: String) = PriceParser.findPrices(text).map { it.value }

    @Test
    fun pricesAboveOneThousandAreReadInFull() {
        // Bug originale: "1.200,00€" diventava 200,00€
        assertEquals(listOf(1200.0, 1499.0), values("Solo 1.200,00€ invece di 1.499,00€"))
        assertEquals(listOf(1299.0), values("Galaxy S24 Ultra a 1.299€"))
        assertEquals(listOf(1299.99), values("€ 1.299,99"))
        assertEquals(listOf(1299.0), values("1,299.00 €"))
        assertEquals(listOf(1234567.89), values("1.234.567,89 €"))
    }

    @Test
    fun ordinaryPrices() {
        assertEquals(listOf(12.99), values("prezzo 12,99€"))
        assertEquals(listOf(19.99), values("19.99 €"))
        assertEquals(listOf(999.0), values("Prezzo 999 EUR"))
        assertEquals(listOf(9.9), values("Pack da 2 pezzi a 9,90€"))
        assertEquals(listOf(1200.0), values("a 1200€"))
    }

    @Test
    fun parseStoredStrings() {
        assertEquals(1200.0, PriceParser.parse("1.200,00 €")!!, 0.001)
        assertEquals(1200.0, PriceParser.parse("1200,00 €")!!, 0.001)
        assertEquals(19.99, PriceParser.parse("19.99 €")!!, 0.001)
        assertEquals(0.99, PriceParser.parse("0,99 €")!!, 0.001)
        assertNull(PriceParser.parse("Vedi offerta"))
        assertNull(PriceParser.parse("Gratis"))
    }

    @Test
    fun formatUsesItalianThousandsSeparator() {
        assertEquals("1.200,00 €", PriceParser.format(1200.0))
        assertEquals("9,90 €", PriceParser.format(9.9))
    }
}
