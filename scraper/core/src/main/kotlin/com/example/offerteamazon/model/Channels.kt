package com.example.offerteamazon.model

/** Canali Telegram usati sia dall'app (lettura locale di riserva) sia dallo scraper centrale. */
object DefaultChannels {
    val initial = listOf(
        "scontiamolo", "meritasconti", "offertepuntotech", "offertepuntocasa",
        "offertepuntomoda", "prezzialribasso", "offerteogniora", "offertedale",
        "GM_ErroriDiPrezzo_SuperAffari", "GosuMania_Tech", "GosuMania_Casa", "GosuMania_Moda"
    )

    val blacklist = listOf(
        "offertemania", "mistercoupon", "lurlodellosconto", "offertepiu",
        "la_pagina_degli_sconti", "scontissimo_amazon", "hdblog_offerte",
        "offertetech", "tariffando", "tuttoandroid", "offerteclub", "offerteclubtech",
        "PromoMania", "promomaniaofficial", "Offerte_PromoMania", "promomania",
        "offertesmartworld", "tuttotechofferte", "offertedalweb",
        "Ultimora_offerte", "ultimora_offerte", "Super_sconti_amazon", "super_sconti_amazon"
    )

    fun isBlacklisted(channel: String): Boolean = blacklist.any { it.equals(channel, ignoreCase = true) }
}

/** Chiave di deduplicazione: lo stesso prodotto (ASIN) pubblicato da più canali è un'offerta sola. */
object OfferDedup {
    private val asinPatterns = listOf(
        Regex("""/(?:dp|gp/product|exec/obidos/ASIN|gp/aw/d|product)/([A-Z0-9]{10})""", RegexOption.IGNORE_CASE),
        Regex("""(?:ASIN=|asin=|dp/)([A-Z0-9]{10})""", RegexOption.IGNORE_CASE),
    )
    private val asinLike = Regex("[A-Z0-9]{10}")

    fun asinOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        for (pattern in asinPatterns) {
            pattern.find(url)?.let { return it.groupValues[1].uppercase() }
        }
        return null
    }

    /**
     * Stesso prodotto = stesso ASIN. I titoli generici dei post non identificano un prodotto,
     * quindi senza ASIN si usa l'id del post.
     */
    fun key(offer: Offer): String {
        val asin = asinOf(offer.amazonUrl) ?: offer.id.takeIf { it.length == 10 && asinLike.matches(it) }
        return if (asin != null) "ASIN_$asin" else "ID_${offer.id}"
    }

    /** A parità di prodotto vince il post più recente (un prezzo vecchio può essere scaduto). */
    fun deduplicate(offers: List<Offer>): List<Offer> =
        offers.groupBy { key(it) }.map { (_, group) ->
            group.maxWithOrNull(
                compareBy<Offer> { it.timestamp }.thenBy { -com.example.offerteamazon.util.PriceParser.sortValue(it.discountedPrice) }
            ) ?: group.first()
        }
}
