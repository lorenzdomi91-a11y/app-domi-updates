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
}
