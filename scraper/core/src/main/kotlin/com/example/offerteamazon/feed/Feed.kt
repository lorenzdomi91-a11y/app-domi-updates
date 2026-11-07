package com.example.offerteamazon.feed

import com.example.offerteamazon.model.Offer
import com.example.offerteamazon.util.VLog
import com.google.gson.GsonBuilder
import java.net.HttpURLConnection
import java.net.URI

/** Indirizzi pubblici (repository app-domi-updates) letti dall'app. */
object VenatorEndpoints {
    private const val BASE = "https://raw.githubusercontent.com/lorenzdomi91-a11y/app-domi-updates"

    /** Offerte raccolte dallo scraper su GitHub Actions (branch "data", sovrascritto a ogni giro). */
    const val FEED_URL = "$BASE/data/offerte.json"

    /** Impostazioni modificabili senza aggiornare l'app (PIN, annunci, tag affiliato...). */
    const val CONFIG_URL = "$BASE/main/config.json"

    const val PRIVACY_URL = "https://github.com/lorenzdomi91-a11y/app-domi-updates/blob/main/PRIVACY.md"
}

/** Feed centrale delle offerte. */
data class OfferFeed(
    val generatedAt: Long,
    val channels: List<String>,
    val offers: List<Offer>,
)

// DTO letti/scritti da Gson: campi tutti opzionali, così un JSON incompleto non fa crashare l'app.
// Nota R8: il package "feed" è escluso dall'offuscamento in proguard-rules.pro.
class FeedOfferDto {
    var id: String? = null
    var title: String? = null
    var description: String? = null
    var originalPrice: String? = null
    var discountedPrice: String? = null
    var discountPercentage: Int? = null
    var imageUrl: String? = null
    var amazonUrl: String? = null
    var source: String? = null
    var category: String? = null
    var couponCode: String? = null
    var dealScore: Int? = null
    var aiInsight: String? = null
    var timestamp: Long? = null
}

class OfferFeedDto {
    var version: Int? = null
    var generatedAt: Long? = null
    var channels: List<String>? = null
    var offers: List<FeedOfferDto>? = null
}

object OfferFeedCodec {
    const val FORMAT_VERSION = 1
    private const val MAX_DESCRIPTION = 700

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(feed: OfferFeed): String {
        val dto = OfferFeedDto().apply {
            version = FORMAT_VERSION
            generatedAt = feed.generatedAt
            channels = feed.channels
            offers = feed.offers.map { it.toDto() }
        }
        return gson.toJson(dto)
    }

    fun decode(json: String): OfferFeed? = try {
        val dto = gson.fromJson(json, OfferFeedDto::class.java)
        if (dto == null || (dto.version ?: 0) > FORMAT_VERSION) {
            null
        } else {
            OfferFeed(
                generatedAt = dto.generatedAt ?: 0L,
                channels = dto.channels.orEmpty(),
                offers = dto.offers.orEmpty().mapNotNull { it.toOffer() },
            )
        }
    } catch (e: Exception) {
        VLog.e("VENATOR", "Feed JSON non valido: ${e.message}")
        null
    }

    private fun Offer.toDto() = FeedOfferDto().also {
        it.id = id
        it.title = title
        it.description = description.take(MAX_DESCRIPTION)
        it.originalPrice = originalPrice
        it.discountedPrice = discountedPrice
        it.discountPercentage = discountPercentage
        it.imageUrl = imageUrl
        it.amazonUrl = amazonUrl
        it.source = source
        it.category = category
        it.couponCode = couponCode
        it.dealScore = dealScore
        it.aiInsight = aiInsight
        it.timestamp = timestamp
    }

    private fun FeedOfferDto.toOffer(): Offer? {
        val id = id?.takeIf { it.isNotBlank() } ?: return null
        val url = amazonUrl?.takeIf { it.isNotBlank() } ?: return null
        return Offer(
            id = id,
            title = title.orEmpty().ifBlank { "Offerta Amazon" },
            description = description.orEmpty(),
            originalPrice = originalPrice,
            discountedPrice = discountedPrice ?: "Vedi offerta",
            discountPercentage = discountPercentage,
            imageUrl = imageUrl.orEmpty(),
            amazonUrl = url,
            source = source.orEmpty().ifBlank { "Telegram" },
            category = category,
            couponCode = couponCode,
            dealScore = dealScore ?: 0,
            aiInsight = aiInsight,
            timestamp = timestamp ?: 0L,
        )
    }
}

/** Configurazione remota (config.json). Tutti i campi sono opzionali. */
class RemoteConfigDto {
    /** SHA-256 esadecimale di "venator:" + PIN. */
    var moderatorPinSha256: String? = null

    /** SHA-256 esadecimale di "venator:" + codice promo in minuscolo. */
    var promoCodesSha256: List<String>? = null

    /** false = annunci di prova Google; true = annunci reali (solo quando l'account AdMob è pronto). */
    var realAds: Boolean? = null

    /** Tag affiliato Amazon (es. "venator-21"). Vuoto = link senza tag. */
    var affiliateTag: String? = null

    /** Ore oltre le quali il feed centrale è considerato vecchio e l'app legge Telegram da sola. */
    var feedMaxAgeHours: Int? = null
}

object RemoteConfigCodec {
    private val gson = GsonBuilder().create()

    fun decode(json: String): RemoteConfigDto? = try {
        gson.fromJson(json, RemoteConfigDto::class.java)
    } catch (e: Exception) {
        VLog.e("VENATOR", "config.json non valido: ${e.message}")
        null
    }
}

/** Download di un file di testo con timeout e limite di dimensione. */
object HttpText {
    fun get(url: String, timeoutMs: Int = 10_000, maxBytes: Int = 8 * 1024 * 1024): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "VENATOR-App")
                setRequestProperty("Accept", "application/json")
                useCaches = false
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                VLog.w("VENATOR", "HTTP ${conn.responseCode} per $url")
                return null
            }
            val bytes = conn.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > maxBytes) error("risposta troppo grande")
                }
                buffer.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            VLog.w("VENATOR", "Download fallito ($url): ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
