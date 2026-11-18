package com.example.offerteamazon.model

/**
 * Immagini prodotto, in ordine di preferenza:
 *  1. foto Amazon ricavata dall'ASIN (sempre il prodotto vero; per ~1/3 dei prodotti Amazon
 *     restituisce un'immagine vuota 1x1 e si passa alla successiva);
 *  2. foto allegata al post Telegram (alcuni canali allegano il proprio logo/mascotte).
 */
object ProductImages {
    fun amazonImageUrl(asin: String): String =
        "https://m.media-amazon.com/images/P/$asin.01._SCLZZZZZZZ_SX500_.jpg"

    fun candidates(offer: Offer): List<String> {
        val list = mutableListOf<String>()
        OfferDedup.asinOf(offer.amazonUrl)?.let { list += amazonImageUrl(it) }
        val telegram = offer.imageUrl
        if (telegram.startsWith("http") && !telegram.contains("/images/P/")) list += telegram
        return list.distinct()
    }

    /** Un'immagine di 1-2 pixel è il segnaposto vuoto di Amazon, non una foto. */
    fun isEmptyPlaceholder(width: Int, height: Int): Boolean = width in 1..2 || height in 1..2
}
