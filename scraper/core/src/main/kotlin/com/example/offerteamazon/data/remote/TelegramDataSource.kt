package com.example.offerteamazon.data.remote

import com.example.offerteamazon.util.VLog as Log
import com.example.offerteamazon.model.Categories
import com.example.offerteamazon.model.Offer
import com.example.offerteamazon.util.PriceParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class TelegramDataSource {

    // Short link (amzn.to / amzn.eu) -> URL Amazon completo, per non risolverlo a ogni refresh.
    private val urlCache = ConcurrentHashMap<String, String>()
    private val wordRegexCache = ConcurrentHashMap<String, Regex>()

    private val userAgents = listOf(
        "Mozilla/5.0 (Linux; Android 14; HONOR PTP-N49) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.6533.103 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    )

    suspend fun fetchTelegramOffers(channelName: String): List<Offer> = withContext(Dispatchers.IO) {
        try {
            com.example.offerteamazon.util.retryWithExponentialBackoff(times = 3, initialDelayMs = 1000) {
                val url = "https://t.me/s/$channelName"
                val userAgent = userAgents.random()

                Log.d("VENATOR", "Fetching channel: $channelName via exponential backoff")

                val doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Cache-Control", "max-age=0")
                    .followRedirects(true)
                    .timeout(20000)
                    .get()

                val htmlBody = doc.body().html()

                if (htmlBody.contains("tgme_widget_message_error")) {
                    Log.e("VENATOR", "Channel $channelName might be private or restricted.")
                    return@retryWithExponentialBackoff emptyList<Offer>()
                }

                var messages = doc.select(".tgme_widget_message_wrap")
                if (messages.isEmpty()) messages = doc.select(".tgme_widget_message")

                if (messages.isEmpty()) {
                    Log.w("VENATOR", "No messages found for $channelName. Title: ${doc.title()}")
                    return@retryWithExponentialBackoff emptyList<Offer>()
                }

                val messageList = messages.takeLast(30)
                Log.d("VENATOR", "Channel $channelName: processing ${messageList.size} messages.")

                coroutineScope {
                    messageList.map { message ->
                        async {
                            try {
                                processMessage(message, channelName)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("VENATOR", "Error processing message: ${e.message}")
                                null
                            }
                        }
                    }.mapNotNull { it.await() }.reversed()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("VENATOR", "Error fetching $channelName after retries: ${e.message}")
            emptyList()
        }
    }

    private fun processMessage(message: Element, channelName: String): Offer? {
        val textElement = message.select(".tgme_widget_message_text").first() 
            ?: message.select("div[class*='message_text']").first()
            
        if (textElement == null) return null
        
        // text() di Jsoup unisce tutto su una riga: serve il testo con gli a capo
        // per riconoscere la riga del titolo.
        val fullText = textWithLineBreaks(textElement)

        // Extract links
        val links = mutableSetOf<String>()
        textElement.select("a[href]").forEach { links.add(it.attr("href")) }
        message.select(".tgme_widget_message_inline_button").forEach { links.add(it.attr("href")) }
        
        // Find the Amazon link (istantaneo in memoria)
        var finalUrl: String? = null
        for (link in links) {
            val low = link.lowercase()
            if ((low.contains("amazon.it") || low.contains("amzn.to") || low.contains("amzn.eu") || low.contains("amzn.asia")) && 
                !low.contains("play.google.com") && !low.contains("apple.com")) {
                finalUrl = link
                break
            }
        }
        
        val linkUrl: String = finalUrl ?: return null
        var amazonUrl: String = linkUrl

        var asin = extractAsin(linkUrl)
        if (asin == null && isShortAmazonLink(linkUrl)) {
            // amzn.to / amzn.eu: senza ASIN mancavano immagine, grafico prezzi e deduplicazione.
            val resolved: String = urlCache[linkUrl] ?: followRedirects(linkUrl)
            urlCache[linkUrl] = resolved
            val resolvedAsin = extractAsin(resolved)
            if (resolvedAsin != null) {
                asin = resolvedAsin
                amazonUrl = resolved
            }
        }
        val postId = message.attr("data-post").takeIf { it.isNotEmpty() }
            ?: message.select("a.tgme_widget_message_date").attr("href").takeIf { it.isNotEmpty() }
        val offerId = asin ?: postId ?: "${channelName}_${fullText.hashCode()}"

        val priceData = extractSemanticPrice(textElement)
        
        var imageUrl = extractImageUrl(message)
        if ((imageUrl.isEmpty()) && (asin != null)) {
            imageUrl = "https://m.media-amazon.com/images/P/$asin.01.jpg"
        }

        val title = extractProductTitle(textElement, fullText)

        val category = extractAdvancedCategory(title, fullText)
        val score = calculateDealScore(priceData.percentage, category, fullText)
        val insight = generateAiInsight(priceData.percentage, category, fullText, score)
        val couponCode = extractCoupon(fullText)
        
        // CUSTOM REQUIREMENT: Keep ONLY t.me links in description
        val cleanedDescription = cleanDescription(fullText)

        return Offer(
            id = offerId,
            title = title,
            description = cleanedDescription,
            originalPrice = priceData.original,
            discountedPrice = priceData.discounted,
            discountPercentage = priceData.percentage,
            imageUrl = imageUrl,
            amazonUrl = amazonUrl,
            source = channelName.replaceFirstChar { it.titlecase() },
            category = category,
            couponCode = couponCode,
            dealScore = score,
            aiInsight = insight,
            timestamp = extractTimestamp(message),
        )
    }

    private fun generateAiInsight(percentage: Int?, category: String, text: String, score: Int): String {
        val t = text.lowercase()
        return when {
            category == Categories.PRICE_ERROR -> "🔥 ERRORE DI PREZZO - Prendi subito!"
            (percentage ?: 0) >= 70 -> "💎 Sconto Pazzesco (-${percentage}%)"
            t.contains("minimo storico") || t.contains("mai visto") -> "📉 Prezzo più basso di sempre"
            t.contains("bomba") -> "💣 Prezzo Bomba Rilevato"
            (percentage ?: 0) >= 50 -> "✅ Ottimo Affare (-${percentage}%)"
            (percentage ?: 0) >= 30 -> "⚡ Sconto Super (-${percentage}%)"
            t.contains("coupon") || t.contains("codice") -> "🎟️ Codice Sconto Applicabile"
            category == Categories.USED -> "🔄 Seconda Mano Garantita"
            (percentage ?: 0) >= 15 -> "🏷️ Sconto Interessante (-${percentage}%)"
            score >= 60 -> "🤖 Consigliato dall'AI ($score/100)"
            else -> "💡 Analizzato dall'AI (Top Quality)"
        }
    }

    private fun cleanDescription(text: String): String {
        val urlRegex = """https?://[^\s<>"]+|www\.[^\s<>"]+""".toRegex()
        return urlRegex.replace(text) { result ->
            val url = result.value.lowercase()
            if (url.contains("t.me/")) result.value else ""
        }
            .lines()
            .joinToString("\n") { it.replace(Regex("[ \\t\u00A0]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Testo del messaggio con gli a capo dei tag <br> (Element.text() li elimina). */
    private fun textWithLineBreaks(element: Element): String {
        val copy = element.clone()
        copy.select("br").forEach { it.replaceWith(TextNode("\n")) }
        return copy.wholeText()
            .replace('\u00A0', ' ')
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractAdvancedCategory(title: String, text: String): String {
        val titleLower = title.lowercase().trim()
        val textLower = text.lowercase().trim()

        // 0. "Errore di prezzo" e "usato/warehouse" sono scritti nel corpo del post, quasi mai
        // nel titolo: vanno cercati nell'intero testo prima della categoria del prodotto.
        val bodyCategory = matchCategoryFromText(textLower.substringBefore("t.me/"))
        if (bodyCategory == Categories.PRICE_ERROR || bodyCategory == Categories.USED) {
            return bodyCategory
        }

        // 1. Priorità Assoluta: Analisi del TITOLO del Prodotto
        val titleCategory = matchCategoryFromText(titleLower)
        if (titleCategory != Categories.GENERIC) {
            return titleCategory
        }

        // 2. Seconda Priorità: Analisi della Descrizione (Senza la firma ed i link del canale)
        val cleanBodyText = textLower.substringBefore("t.me/").substringBefore("seguici").trim()
        return matchCategoryFromText(cleanBodyText)
    }

    /** Parola intera (evita che "moto" scatti su "Motorola" o "auto" su "automatico"). */
    private fun String.hasWord(word: String): Boolean {
        val regex = wordRegexCache.getOrPut(word) {
            Regex("(?<![\\p{L}\\d])" + Regex.escape(word) + "(?![\\p{L}\\d])")
        }
        return regex.containsMatchIn(this)
    }

    private fun String.hasAny(vararg parts: String) = parts.any { contains(it) }
    private fun String.hasAnyWord(vararg words: String) = words.any { hasWord(it) }

    private fun matchCategoryFromText(t: String): String {
        return when {
            // 1. Errori di Prezzo / Bug
            t.hasAny("errore prezzo", "errore di prezzo", "errori di prezzo", "prezzo errato", "prezzo sbagliato",
                "bug prezzo", "prezzo bug", "price error") -> Categories.PRICE_ERROR

            // 2. Usato / Warehouse / Refurbished
            t.hasAny("warehouse", "seconda mano", "usato garantito", "ricondizionat") -> Categories.USED

            // 3. Audio, Cuffie & Cassa
            t.hasAny("cuffie", "auricolari", "airpods", "soundbar", "cassa bluetooth", "speaker", "headset",
                "subwoofer", "earbuds", "galaxy buds", "amplificatore") ||
                t.hasAnyWord("jbl", "marshall", "bose") -> Categories.AUDIO

            // 4. Smartwatch & Wearable
            t.hasAny("smartwatch", "apple watch", "galaxy watch", "smart band", "amazfit", "fitbit", "garmin") ||
                t.hasWord("band") -> Categories.SMARTWATCH

            // 5. TV & Home Cinema
            t.hasAny("smart tv", "oled", "qled", "proiettore", "fire tv", "chromecast", "televisore") ||
                t.hasWord("tv") -> Categories.TV

            // 6. Smartphone & Tablet
            t.hasAny("smartphone", "iphone", "galaxy s", "galaxy a", "galaxy z", "xiaomi redmi", "redmi note",
                "realme", "google pixel", "cellulare", "tablet", "ipad", "tab s", "caricatore", "powerbank",
                "power bank", "cover iphone", "vetro temperato", "motorola") ||
                t.hasAnyWord("oppo", "honor", "pixel") -> Categories.SMARTPHONE

            // 7. Computer & Notebook
            t.hasAny("laptop", "notebook", "macbook", "monitor pc", "monitor 4k", "monitor gaming", "scheda madre",
                "scheda video", "intel core", "ryzen", "tastiera mecc", "mouse pc", "ssd", "hard disk",
                "stampante", "router", "modem", "mini pc", "pc fisso", "memoria ram", "chiavetta usb") ||
                t.hasAnyWord("rtx", "gtx", "monitor") -> Categories.PC

            // 8. Gaming & Console
            t.hasAny("playstation", "ps5", "ps4", "xbox", "nintendo", "funko", "giochi da tavolo", "joystick",
                "dualsense", "controller xbox", "sedia gaming") ||
                t.hasAnyWord("switch", "lego") -> Categories.GAMING

            // 9. Elettrodomestici & Casa
            t.hasAny("aspirapolvere", "friggitrice", "air fryer", "dreame", "roborock", "dyson", "cucina",
                "caffè", "caffe", "delonghi", "de'longhi", "nespresso", "detersivo", "fairy", "pampers",
                "spazzolone", "pentola", "padella", "materasso", "cuscino", "lampada", "striscia led",
                "scopa elettrica", "forno", "microonde", "lavatrice", "lavastoviglie") ||
                t.hasWord("finish") -> Categories.HOME

            // 10. Beauty & Salute
            t.hasAny("spazzolino", "oral-b", "rasoio", "braun", "oneblade", "piastra capelli", "asciugacapelli",
                "profumo", "crema viso", "shampoo", "bagnoschiuma", "epilatore", "regolabarba", "integratori",
                "misuratore pressione", "saturimetro") ||
                t.hasWord("phon") -> Categories.BEAUTY

            // 11. Auto, Bricolage & Fai da Te
            t.hasAny("trapano", "avvitatore", "chiave inglese", "bosch", "dewalt", "makita", "dashcam",
                "compressore", "fai da te", "giardinaggio", "tagliaerba", "tergicristallo", "autoradio",
                "per auto", "per moto") ||
                t.hasAnyWord("auto", "moto") -> Categories.DIY

            // 12. Abbigliamento & Scarpe
            t.hasAny("scarpe", "sneakers", "nike", "adidas", "geox", "stivali", "ciabatte", "maglietta",
                "t-shirt", "pantaloni", "giacca", "felpa", "zaino", "trolley", "valigia", "orologio da polso",
                "jeans", "intimo") ||
                t.hasWord("puma") -> Categories.FASHION

            else -> Categories.GENERIC
        }
    }

    private fun calculateDealScore(percentage: Int?, category: String, text: String): Int {
        var score = 35 // Base score slightly higher
        
        // Percentage weight (up to 45 points)
        percentage?.let { 
            score += (it / 2).coerceAtMost(45)
        }
        
        // Category weight
        if (category == Categories.PRICE_ERROR) score += 30
        if (category == Categories.USED) score += 5
        
        // Keyword density and quality (up to 20 points)
        val t = text.lowercase()
        if (t.contains("minimo storico") || t.contains("mai visto")) score += 15
        if (t.contains("bomba") || t.contains("🔥") || t.contains("⚡")) score += 5
        if (t.contains("codice") || t.contains("coupon")) score += 10 // Coupons are usually good
        
        // Penalize short descriptions or titles (likely low quality)
        if (text.length < 50) score -= 10
        
        return score.coerceIn(0, 100)
    }

    private fun isShortAmazonLink(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("amzn.to/") || low.contains("amzn.eu/") || low.contains("amzn.asia/")
    }

    /** Segue i redirect di un short link fino all'URL Amazon con l'ASIN (max 4 salti). */
    private fun followRedirects(url: String): String {
        if (extractAsin(url) != null) return url
        var currentUrl = url
        try {
            repeat(4) {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", userAgents.first())
                val location = try {
                    conn.connect()
                    conn.getHeaderField("Location")
                } finally {
                    conn.disconnect()
                }
                if (location.isNullOrEmpty()) return currentUrl
                currentUrl = URL(URL(currentUrl), location).toString()
                if (extractAsin(currentUrl) != null) return currentUrl
            }
        } catch (e: Exception) {
            Log.w("VENATOR", "Short link not resolved ($url): ${e.message}")
        }
        return currentUrl
    }

    private fun extractSemanticPrice(element: Element): PriceResult {
        val text = element.text()
        val matches = PriceParser.findPrices(text)

        if (matches.isEmpty()) return PriceResult("Vedi offerta", percentage = extractPercentage(text))

        // Testi barrati / in grassetto: indicano prezzo di listino / prezzo finale.
        val struckTexts = element.select("s, strike, del").map { it.text() }
        val boldTexts = element.select("b, strong").map { it.text() }

        val candidates = matches.mapIndexed { index, match ->
            // Contesto = solo il testo tra il prezzo precedente e questo, così "listino 1.499€ ora 1.199€"
            // non attribuisce "listino" anche al secondo prezzo.
            val prevEnd = if (index > 0) matches[index - 1].end else 0
            val ctxStart = maxOf(prevEnd, match.start - 25)
            val contextBefore = text.substring(ctxStart, match.start).lowercase()

            val isStripped = struckTexts.any { it.contains(match.number) }
            val isBold = boldTexts.any { it.contains(match.number) }
            val isOriginalHint = ORIGINAL_PRICE_HINT.containsMatchIn(contextBefore)
            val isDiscountAmount = DISCOUNT_AMOUNT_HINT.containsMatchIn(contextBefore) ||
                contextBefore.trimEnd().endsWith("-")

            var score = 0
            if (!isOriginalHint && FINAL_PRICE_HINT.containsMatchIn(contextBefore)) score += 60
            if (isOriginalHint) score -= 40
            if (isDiscountAmount) score -= 80
            if (isBold) score += 25
            if (isStripped) score -= 100

            PriceCandidateSemantic(match.value, isStripped, isOriginalHint, isDiscountAmount, score, match.start)
        }

        val priceCandidates = candidates.filter { !it.isDiscountAmount && it.value > 0.0 }
            .ifEmpty { candidates }

        // Prezzo da pagare: punteggio più alto; a parità, il valore più basso.
        val winner = priceCandidates
            .sortedWith(compareByDescending<PriceCandidateSemantic> { it.score }.thenBy { it.value })
            .first()

        // Prezzo originale: barrato o introdotto da "listino/invece di/era...".
        // Senza indizi lo accettiamo solo se il post ha esattamente due prezzi e quello finale
        // è introdotto da "ora/a/➡️...", per non trasformare varianti (128GB 899€ / 256GB 999€)
        // in uno sconto finto.
        val others = priceCandidates.filter { it !== winner && it.value > winner.value * 1.02 }
        val originalCandidate = others.filter { it.isStripped || it.isOriginalHint }.maxByOrNull { it.value }
            ?: others.singleOrNull()?.takeIf { priceCandidates.size == 2 && winner.score > 0 }

        var percentage: Int? = null
        if (originalCandidate != null && originalCandidate.value > 0.0) {
            percentage = (((originalCandidate.value - winner.value) / originalCandidate.value) * 100)
                .toInt().coerceIn(1, 99)
        }
        if (percentage == null) percentage = extractPercentage(text)

        return PriceResult(
            discounted = PriceParser.format(winner.value),
            original = originalCandidate?.let { PriceParser.format(it.value) },
            percentage = percentage,
        )
    }

    /** Percentuale scritta nel post, es. "-30%" o "30 %". */
    private fun extractPercentage(text: String): Int? =
        Regex("""(?<![\d.,])(\d{1,2})\s*%""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..99 }

    private fun extractImageUrl(message: Element): String {
        val selectors = listOf(
            ".tgme_widget_message_photo_wrap", 
            ".tgme_widget_message_link_preview_image",
            ".tgme_widget_message_video_player i",
            ".link_preview_image",
            ".link_preview_right_image",
        )
        for (s in selectors) {
            val element = message.select(s).first() ?: continue
            val style = element.attr("style")
            // Try to extract from background-image url()
            val url = """url\(['"]?(.*?)['"]?\)""".toRegex().find(style)?.groupValues?.get(1)
            if (!url.isNullOrEmpty()) return if (url.startsWith("//")) "https:$url" else url
        }
        // Fallback 1: try finding any image tag
        message.select("img").forEach { 
            val src = it.attr("src")
            if (src.isNotEmpty() && !src.contains("blank.gif")) {
                return if (src.startsWith("//")) "https:$src" else src
            }
        }
        // Fallback 2: data-src
        message.select("[data-src]").firstOrNull()?.attr("data-src")?.let {
            if (it.isNotEmpty()) return if (it.startsWith("//")) "https:$it" else it
        }
        return ""
    }

    private fun extractAsin(url: String): String? {
        val patterns = listOf(
            """/dp/([A-Z0-9]{10})""", 
            """/product/([A-Z0-9]{10})""", 
            """asin=([A-Z0-9]{10})""",
            """/aw/d/([A-Z0-9]{10})""",
            """/gp/product/([A-Z0-9]{10})"""
        )
        for (p in patterns) {
            p.toRegex(RegexOption.IGNORE_CASE).find(url)?.let { return it.groupValues[1].uppercase() }
        }
        return null
    }

    private fun extractProductTitle(textElement: Element, fullText: String): String {
        // 1. Try finding a valid bold/strong element that is NOT a promotional badge
        val boldElements = textElement.select("b, strong")
        for (b in boldElements) {
            val rawText = b.text().trim()
            val cleaned = cleanAndExtractTitle(rawText)
            if (cleaned.length >= 6 && !isPromoBadge(rawText) && !isPromoBadge(cleaned)) {
                return cleaned
            }
        }

        // 2. If no valid bold tag was found, iterate line by line in fullText
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            val cleaned = cleanAndExtractTitle(line)
            if (cleaned.length >= 6 && !isPromoBadge(line) && !isPromoBadge(cleaned)) {
                return cleaned
            }
        }

        // 3. Fallback: find any line that yields a reasonable title
        for (line in lines) {
            val cleaned = cleanAndExtractTitle(line)
            if (cleaned.length >= 4) return cleaned
        }

        return "Offerta Amazon"
    }

    private fun isPromoBadge(text: String): Boolean {
        val clean = text.lowercase()
            .replace(Regex("[^a-z0-9àèéìòù ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val promoWords = setOf(
            "minimo storico", "storico", "sconto bomba", "prezzo bomba", "errore prezzo",
            "errore di prezzo", "prezzo errato", "prezzo pazzo", "super offerta", "super sconto",
            "offerta lampo", "sconto shock", "incredibile", "scontissimo", "codice sconto",
            "coupon", "coupon sconto", "prezzo crollato", "crolla il prezzo", "mai visto",
            "offerta shock", "super prezzo", "imperdibile", "solo per oggi", "nuovo minimo",
            "sconto", "offerta", "minimo", "bomba", "shock", "super", "errore", "pazzo",
            "promo", "promozione", "affare", "super affare", "scontato", "ribasso",
            "prezzi al ribasso", "prezzo al ribasso", "scontiamolo", "meritasconti",
            "offertepuntotech", "offertepuntocasa", "offertepuntomoda", "gosumania",
            "prezzialribasso", "offerteogniora", "offertedale"
        )

        if (clean in promoWords) return true
        if (clean.length < 5) return true

        val words = clean.split(" ").filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words.all { it in promoWords || it.matches(Regex("\\d+%?")) || it.length <= 2 }) return true

        return false
    }

    private fun cleanAndExtractTitle(rawTitle: String): String {
        var text = rawTitle

        // 1. Remove URLs
        text = text.replace(Regex("https?://\\S+|www\\.\\S+|t\\.me/\\S+"), "")

        // 2. Strip price patterns (e.g., "299,00 €", "299.99€", "299€", "<s>...</s>")
        text = text.replace(Regex("<s>.*?</s>"), "")
        text = text.replace(TITLE_PRICE_WITH_PREFIX, "")
        text = text.replace(PriceParser.PRICE_REGEX, "")

        // 3. Remove leading emojis and symbols
        text = text.replace(Regex("^[\\s\\p{So}\\p{Cn}\\p{Cs}🔥💣⚡🚨💎✅🎉🏷️❗‼🚀📉⭐💥🏆🎁📌👉👈►•\\-–—:*#]+"), "").trim()

        // 4. Remove leading promotional prefix phrases
        val leadingPromoRegex = Regex("(?i)^(?:(?:minimo storico|sconto bomba|prezzo bomba|prezzi al ribasso|prezzo al ribasso|errore (?:di )?prezzo|prezzo errato|super offerta|offerta lampo|sconto shock|codice sconto|coupon|super sconto|super|bomba|shock|incredibile|scontissimo|offerta|prezzo crollato)[!.:;\\-\\s]*)+")
        text = text.replace(leadingPromoRegex, "").trim()

        // 5. Remove trailing emojis and clutter
        text = text.replace(Regex("[\\s\\p{So}\\p{Cn}\\p{Cs}🔥💣⚡🚨💎✅🎉🏷️❗‼🚀📉⭐💥🏆🎁📌👉👈►•\\-–—:*#]+$"), "").trim()

        // 6. Cleanup extra spaces
        text = text.replace(Regex("\\s+"), " ").trim()

        return text.take(120)
    }

    private fun extractCoupon(text: String): String? {
        // Solo la parola chiave è case-insensitive: il codice deve essere MAIUSCOLO/cifre,
        // altrimenti "Codice sconto" restituiva "sconto" come coupon.
        val code = COUPON_REGEX.findAll(text)
            .map { it.groupValues[1].trimEnd('-') }
            .firstOrNull { it.length >= 4 && it !in COUPON_STOP_WORDS }
        if (code != null) return code

        val t = text.lowercase()
        if (t.contains("coupon") && (t.contains("in pagina") || t.contains("spunta") || t.contains("applica"))) {
            return "Applica in pagina"
        }
        return null
    }

    private fun extractTimestamp(message: Element): Long {
        val now = System.currentTimeMillis()
        try {
            val timeElement = message.select("time").first()
            val dt = timeElement?.attr("datetime")
            if (!dt.isNullOrEmpty()) {
                val cleanDt = dt.replace("Z", "").replace("+00:00", "")
                val parseText = if (cleanDt.length >= 19) cleanDt.substring(0, 19) else cleanDt
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(parseText)?.time
                if (parsed != null && parsed <= (now + 3600000L)) {
                    return parsed // Older than the 7-day cutoff is discarded by repository.
                }
            }
        } catch (e: Exception) {
            Log.e("VENATOR", "Error parsing timestamp: ${e.message}")
        }
        return now
    }

    private data class PriceCandidateSemantic(
        val value: Double,
        val isStripped: Boolean,
        val isOriginalHint: Boolean,
        val isDiscountAmount: Boolean,
        val score: Int,
        val position: Int,
    )
    private data class PriceResult(val discounted: String, val original: String? = null, val percentage: Int? = null)

    private companion object {
        // Le parole chiave devono stare subito prima del prezzo (al massimo pochi caratteri in mezzo).
        // Parola intera senza \b: \b non tratta bene le lettere accentate ("anziché").
        private const val WB = """(?<![\p{L}\d])"""
        private const val WE = """(?![\p{L}\d])"""
        val FINAL_PRICE_HINT = Regex(
            """(?:$WB(?:solo|soli|paghi|ora|adesso|prezzo|finale|scende a|crolla a|a)$WE|➡|👉|→)[^€\d]{0,15}$"""
        )
        val ORIGINAL_PRICE_HINT = Regex(
            """$WB(?:listino|invece di|anziché|anziche|era|consigliato|prima|da)$WE[^€\d]{0,15}$"""
        )
        val DISCOUNT_AMOUNT_HINT = Regex(
            """$WB(?:sconto|risparmi|risparmio|risparmia|coupon|buono)(?:\s+(?:di|da|del|extra))?\s*[:\-]?\s*$"""
        )
        val TITLE_PRICE_WITH_PREFIX = Regex(
            """(?i)\b(?:a soli|anziché|invece di|prezzo|listino|ora a|da|a)\s*(?:""" + PriceParser.PRICE_REGEX.pattern + ")"
        )
        val COUPON_REGEX = Regex("""(?i:codice(?:\s+sconto)?|coupon|code|promo)\s*:?\s*([A-Z0-9][A-Z0-9-]{3,24})(?![\p{L}\d])""")
        val COUPON_STOP_WORDS = setOf("SCONTO", "COUPON", "CODICE", "PROMO", "AMAZON", "APPLICA", "OFFERTA")
    }
}
