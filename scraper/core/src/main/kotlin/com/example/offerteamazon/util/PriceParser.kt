package com.example.offerteamazon.util

import java.util.Locale

/**
 * Lettura e formattazione dei prezzi in euro.
 *
 * Gestisce i separatori delle migliaia: prima "1.200,00€" veniva letto come "200,00€"
 * perché la vecchia regex non conosceva il punto delle migliaia.
 *
 * Formati riconosciuti: 1.299,99 € · 1.299 € · 1299,99€ · 1299€ · €1.299 · 1,299.99 € · 1299 EUR
 */
object PriceParser {

    /** Numero con separatori di migliaia opzionali (stile italiano o inglese). */
    private const val NUMBER =
        """\d{1,3}(?:[.  ]\d{3})+(?:,\d{1,2})?""" + // 1.299  1.299,99  1 299,99 (spazio non separabile)
            """|\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?""" +         // 1,299  1,299.99
            """|\d+(?:[.,]\d{1,2})?"""                         // 1299  1299,99  12.5

    /** Un prezzo con il simbolo € davanti (gruppo 1) o dietro / "EUR" / "euro" (gruppo 2). */
    val PRICE_REGEX = Regex(
        """€\s*($NUMBER)(?!\d)|(?<![\d.,])($NUMBER)(?!\d)\s*(?:€|(?i:euro|eur)\b)"""
    )

    data class PriceMatch(
        val value: Double,
        /** Testo del numero così come compare nel messaggio, es. "1.299,00". */
        val number: String,
        val start: Int,
        val end: Int,
    )

    /** Tutti i prezzi trovati nel testo, in ordine di apparizione. */
    fun findPrices(text: String): List<PriceMatch> =
        PRICE_REGEX.findAll(text).mapNotNull { m ->
            val number = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val value = parse(number) ?: return@mapNotNull null
            PriceMatch(value, number, m.range.first, m.range.last + 1)
        }.toList()

    /**
     * Converte una stringa prezzo in Double. Restituisce null se non è un prezzo
     * (es. "Vedi offerta", "Gratis").
     */
    fun parse(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val s = raw.replace(Regex("[^\\d.,]"), "").trim('.', ',')
        if (s.isEmpty() || s.none { it.isDigit() }) return null

        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val normalized = when {
            // Entrambi presenti: l'ultimo separatore è quello decimale.
            lastDot >= 0 && lastComma >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            lastComma >= 0 -> normalizeSingleSeparator(s, ',')
            lastDot >= 0 -> normalizeSingleSeparator(s, '.')
            else -> s
        }
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun normalizeSingleSeparator(s: String, sep: Char): String {
        val parts = s.split(sep)
        // "1.299" o "1.299.000" -> separatore delle migliaia (nessuno scrive 3 decimali sui prezzi)
        val looksLikeThousands = parts.size > 1 &&
            parts.first().length in 1..3 &&
            parts.first() != "0" &&
            parts.drop(1).all { it.length == 3 }
        return when {
            looksLikeThousands -> parts.joinToString("")
            parts.size == 2 -> "${parts[0]}.${parts[1]}"
            else -> parts.dropLast(1).joinToString("") + "." + parts.last()
        }
    }

    /** Formato di visualizzazione italiano: 1.299,00 € */
    fun format(value: Double): String = String.format(Locale.ITALY, "%,.2f €", value)

    /** Valore numerico per ordinamenti: i prezzi non numerici finiscono in fondo. */
    fun sortValue(price: String?): Double = parse(price) ?: Double.MAX_VALUE
}
