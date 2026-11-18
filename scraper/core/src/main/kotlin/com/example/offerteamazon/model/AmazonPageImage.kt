package com.example.offerteamazon.model

/**
 * Foto principale di un prodotto letta dalla pagina Amazon (versione mobile o desktop).
 * Serve quando la foto "rapida" dall'ASIN (ProductImages) è vuota.
 */
object AmazonPageImage {
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun pageUrl(asin: String) = "https://www.amazon.it/dp/$asin"

    private val imgTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attr = { name: String -> Regex("""\b$name="([^"]+)"""", RegexOption.IGNORE_CASE) }
    private val hiRes = Regex(""""hiRes":"(https://m\.media-amazon\.com/images/I/[^"]+)"""")
    private val amazonImage = Regex("""^https://m\.media-amazon\.com/images/I/[^"\s]+\.(?:jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)

    fun isCaptcha(html: String): Boolean =
        html.length < 20_000 && (html.contains("captcha", ignoreCase = true) || html.contains("validateCaptcha"))

    /** URL della foto principale, oppure null (pagina di verifica, prodotto senza foto, formato sconosciuto). */
    fun extract(html: String): String? {
        if (isCaptcha(html)) return null
        // 1. Pagina mobile: <img id="main-image" data-a-hires="...">
        // 2. Pagina desktop: <img id="landingImage" data-old-hires="..." src="...">
        for (id in listOf("main-image", "landingImage", "imgBlkFront")) {
            val tag = imgTag.findAll(html).firstOrNull { it.value.contains("id=\"$id\"") }?.value ?: continue
            for (name in listOf("data-a-hires", "data-old-hires", "src")) {
                val value = attr(name).find(tag)?.groupValues?.get(1)?.replace("&amp;", "&")
                if (value != null && amazonImage.matches(value)) return value
            }
        }
        // 3. Dati della galleria (desktop)
        return hiRes.find(html)?.groupValues?.get(1)
    }
}
