package com.example.offerteamazon.scraper

import com.example.offerteamazon.data.remote.TelegramDataSource
import com.example.offerteamazon.feed.OfferFeed
import com.example.offerteamazon.feed.OfferFeedCodec
import com.example.offerteamazon.model.DefaultChannels
import com.example.offerteamazon.model.Offer
import com.example.offerteamazon.model.OfferDedup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.system.exitProcess

/**
 * Scraper centrale: legge i canali Telegram una volta per tutti e scrive offerte.json.
 * Gira su GitHub Actions (repository app-domi-updates) ogni 15 minuti.
 *
 * Uso: scraper <offerte.json> [canali.txt]
 *  - offerte.json: file da aggiornare (se esiste, le offerte recenti vengono mantenute)
 *  - canali.txt:   un canale per riga, # per i commenti (facoltativo)
 */
private const val KEEP_HOURS = 72L
private const val MAX_OFFERS = 500

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Uso: scraper <offerte.json> [canali.txt]")
        exitProcess(2)
    }
    val output = File(args[0])
    val channelsFile = args.getOrNull(1)?.let(::File)

    val channels = channelsFile?.takeIf { it.isFile }?.readLines()
        ?.map { it.substringBefore('#').trim().removePrefix("@") }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: DefaultChannels.initial
    val activeChannels = channels.filterNot { DefaultChannels.isBlacklisted(it) }.distinctBy { it.lowercase() }

    val previous = output.takeIf { it.isFile }?.readText()?.let { OfferFeedCodec.decode(it) }
    val dataSource = TelegramDataSource()

    val results: List<Pair<String, List<Offer>>> = runBlocking {
        val slots = Semaphore(4)
        activeChannels.map { channel ->
            async(Dispatchers.IO) {
                slots.withPermit {
                    val offers = try {
                        dataSource.fetchTelegramOffers(channel)
                    } catch (e: Exception) {
                        println("ERRORE $channel: ${e.message}")
                        emptyList()
                    }
                    println("Canale $channel: ${offers.size} offerte")
                    channel to offers
                }
            }
        }.awaitAll()
    }

    val fresh = results.flatMap { it.second }
    val okChannels = results.filter { it.second.isNotEmpty() }.map { it.first }
    println("Totale: ${fresh.size} offerte da ${okChannels.size}/${activeChannels.size} canali")

    val now = System.currentTimeMillis()
    if (fresh.isEmpty() && previous != null) {
        // Telegram non ha risposto: il feed resta com'era (con la sua data), così l'app
        // si accorge che è vecchio e legge i canali da sola.
        println("Nessuna offerta nuova: feed invariato")
        return
    }

    val threshold = now - KEEP_HOURS * 60 * 60 * 1000
    val merged = OfferDedup.deduplicate(previous?.offers.orEmpty() + fresh)
        .filter { it.timestamp >= threshold }
        .sortedByDescending { it.timestamp }
        .take(MAX_OFFERS)

    val feed = OfferFeed(
        generatedAt = now,
        // I canali che l'app non deve più leggere da sola: tutti quelli gestiti qui.
        channels = activeChannels,
        offers = merged,
    )
    output.parentFile?.mkdirs()
    output.writeText(OfferFeedCodec.encode(feed))
    println("Scritto ${output.path}: ${merged.size} offerte")
}
