package com.example.offerteamazon

import com.example.offerteamazon.data.remote.TelegramDataSource
import com.example.offerteamazon.feed.OfferFeed
import com.example.offerteamazon.feed.OfferFeedCodec
import com.example.offerteamazon.model.Categories
import com.example.offerteamazon.model.Offer
import com.example.offerteamazon.model.OfferDedup
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Post Telegram di esempio -> offerta letta correttamente (nessuna rete). */
class TelegramParsingTest {

    private val dataSource = TelegramDataSource()
    private val processMessage = TelegramDataSource::class.java
        .getDeclaredMethod("processMessage", Element::class.java, String::class.java)
        .apply { isAccessible = true }

    private fun parse(html: String): Offer {
        val element = Jsoup.parse(
            """<div class="tgme_widget_message" data-post="chan/1"><div class="tgme_widget_message_text">$html</div>
            <time datetime="2026-11-07T05:00:00+00:00"></time></div>"""
        ).selectFirst(".tgme_widget_message")!!
        val offer = processMessage.invoke(dataSource, element, "testchan") as Offer?
        assertNotNull("post non riconosciuto", offer)
        return offer!!
    }

    @Test
    fun priceAboveOneThousandWithOriginalAndCoupon() {
        val o = parse("""🔥 <b>MINIMO STORICO</b><br><b>Samsung Galaxy S24 Ultra 512GB</b><br>a soli <b>1.199,00€</b> invece di <s>1.499,00€</s><br>Codice sconto: SAVE20<br><a href="https://www.amazon.it/dp/B0CMDRCZBJ?tag=x">link</a>""")
        assertEquals("1.199,00 €", o.discountedPrice)
        assertEquals("1.499,00 €", o.originalPrice)
        assertEquals(20, o.discountPercentage)
        assertEquals("Samsung Galaxy S24 Ultra 512GB", o.title)
        assertEquals("SAVE20", o.couponCode)
        assertEquals(Categories.SMARTPHONE, o.category)
    }

    @Test
    fun motorolaIsNotAutoAndArrowMeansFinalPrice() {
        val o = parse("""Motorola Edge 50 Pro<br>Prezzo 349€ ➡️ ora 299€<br><a href="https://www.amazon.it/dp/B0D1234567">link</a>""")
        assertEquals(Categories.SMARTPHONE, o.category)
        assertEquals("299,00 €", o.discountedPrice)
        assertEquals("349,00 €", o.originalPrice)
    }

    @Test
    fun priceErrorInBodyAndPercentFallback() {
        val o = parse("""ERRORE DI PREZZO 🚨<br>TV LG OLED 65" a 1.049€<br>-45%<br><a href="https://www.amazon.it/dp/B0C1111111">link</a>""")
        assertEquals(Categories.PRICE_ERROR, o.category)
        assertEquals(45, o.discountPercentage)
        assertEquals("1.049,00 €", o.discountedPrice)
    }

    @Test
    fun variantsDoNotCreateFakeDiscount() {
        val o = parse("""iPhone 15 128GB 899€ / 256GB 999€<br><a href="https://www.amazon.it/dp/B0C2222222">link</a>""")
        assertNull(o.originalPrice)
        assertEquals("899,00 €", o.discountedPrice)
    }

    @Test
    fun discountAmountIsNotThePrice() {
        val o = parse("""Friggitrice ad aria Cosori<br>Coupon da spuntare in pagina<br>Sconto di 20€, paghi 79,99€<br><a href="https://www.amazon.it/dp/B0C3333333">link</a>""")
        assertEquals("Applica in pagina", o.couponCode)
        assertEquals("79,99 €", o.discountedPrice)
        assertEquals(Categories.HOME, o.category)
    }

    @Test
    fun feedRoundTripAndDedup() {
        val a = parse("""Cuffie Sony WH-1000XM5<br>a 249€ invece di 399€<br><a href="https://www.amazon.it/dp/B0B1111111">link</a>""")
        val b = a.copy(id = "altro", timestamp = a.timestamp + 1000, discountedPrice = "239,00 €")
        val feed = OfferFeed(generatedAt = 123L, channels = listOf("testchan"), offers = OfferDedup.deduplicate(listOf(a, b)))
        assertEquals(1, feed.offers.size)
        val decoded = OfferFeedCodec.decode(OfferFeedCodec.encode(feed))!!
        assertEquals(123L, decoded.generatedAt)
        assertEquals("239,00 €", decoded.offers.single().discountedPrice)
        assertEquals(a.title, decoded.offers.single().title)
    }

    // --- Casi reali trovati nel feed del 17/11/2026 ---

    @Test
    fun roundupPostUsesOnlyFirstProduct() {
        val o = parse(
            """💻 Migliori offerte tech di oggi<br><br>1) 👉🏻 Blink videocamera esterna 2K+ con proiettore<br>💶 45,99€ invece di 114,97€<br>➡ <a href="https://www.amazon.it/dp/B0D1111AAA">ACQUISTA QUI</a><br><br>""" +
                """2) 👉🏻 Dreame Matrix10 Pro robot aspirapolvere 30.000 Pa<br>💶 569,00€ invece di 999,00€<br>➡ <a href="https://www.amazon.it/dp/B0D2222BBB">ACQUISTA QUI</a><br><br>""" +
                """3) 👉🏻 Beats Studio Buds+ auricolari true wireless<br>💶 89,00€ invece di 174,05€<br>➡ <a href="https://www.amazon.it/dp/B0D3333CCC">ACQUISTA QUI</a>"""
        )
        assertEquals("Blink videocamera esterna 2K+ con proiettore", o.title)
        assertEquals("45,99 €", o.discountedPrice)
        assertEquals("114,97 €", o.originalPrice)
        assertEquals(60, o.discountPercentage)
        assertEquals("B0D1111AAA", o.id)
    }

    @Test
    fun gosuManiaHeaderIsNotTheTitle() {
        val o = parse(
            """<b>OFFERTA + CODICE</b><br><br>🛍Tineco Floor One i5 Stretch Aspirapolvere Lavatrice, 20.000Pa, Piatto a 180°<br>💰Prezzo iniziale: 169,00 €<br>💰Prezzo finale: 140,99 €<br>👾Codice Sconto: DOTINECO<br>🔗<a href="https://www.amazon.it/dp/B0D4444DDD">Link Prodotto</a>🔗"""
        )
        assertEquals("Tineco Floor One i5 Stretch Aspirapolvere Lavatrice, 20.000Pa, Piatto a 180°", o.title)
        assertEquals("140,99 €", o.discountedPrice)
        assertEquals("169,00 €", o.originalPrice)
        assertEquals("DOTINECO", o.couponCode)
        assertEquals(Categories.HOME, o.category)
    }

    @Test
    fun newCategories() {
        fun cat(title: String) = parse("""$title<br>a 9,99€<br><a href="https://www.amazon.it/dp/B0D5555EEE">link</a>""").category
        assertEquals(Categories.FOOD, cat("by Amazon - Cibo per Gatti - 3Kg."))
        assertEquals(Categories.FOOD, cat("Gimoka Espresso Intenso 70 capsule compatibili A Modo Mio"))
        assertEquals(Categories.FOOD, cat("Lagavulin 16 Anni Scotch Single Malt Whisky con Astuccio"))
        assertEquals(Categories.PC, cat("CHERRY XTRFY M68 Wireless, Mouse Gaming Senza Fili"))
        assertEquals(Categories.FASHION, cat("Skechers Uno Golden Air Sneaker da Donna"))
        assertEquals(Categories.BEAUTY, cat("Tangle Teezer - Spazzola per Capelli"))
        assertEquals(Categories.BEAUTY, cat("Magnesio e Potassio 60 capsule integratore"))
        assertEquals(Categories.HOME, cat("De'Longhi macchina caffè con capsule"))
    }

    @Test
    fun sameShortLinkIsOneOffer() {
        val a = parse("""Prodotto misterioso<br>a 19,99€<br><a href="https://amzn.to/46fPopi">link</a>""")
        val b = a.copy(id = "altro_post", timestamp = a.timestamp + 1)
        assertEquals(1, OfferDedup.deduplicate(listOf(a, b)).size)
    }
}
