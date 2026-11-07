package com.example.offerteamazon.model

/**
 * Nomi di categoria condivisi da parser, filtri, griglia Categorie e notifiche.
 * Prima ogni schermata usava regole diverse (es. il filtro "Usato" cercava
 * "USATO GARANTITO" mentre il parser salvava "WAREHOUSE & USATO").
 */
object Categories {
    const val ALL = "Tutte"
    const val PRICE_ERROR = "ERRORE PREZZO"
    const val USED = "WAREHOUSE & USATO"
    const val AUDIO = "AUDIO & CUFFIE"
    const val SMARTWATCH = "SMARTWATCH"
    const val TV = "TV & AUDIO"
    const val SMARTPHONE = "SMARTPHONE & TABLET"
    const val PC = "PC & NOTEBOOK"
    const val GAMING = "GAMING & CONSOLE"
    const val HOME = "ELETTRODOMESTICI & CASA"
    const val BEAUTY = "BEAUTY & SALUTE"
    const val DIY = "AUTO & FAI DA TE"
    const val FASHION = "ABBIGLIAMENTO & SCARPE"
    const val GENERIC = "OFFERTA"

    /** Riconduce nomi storici o alternativi (push FCM, versioni vecchie) al nome canonico. */
    fun canonical(category: String?): String {
        val c = (category ?: GENERIC).trim().uppercase()
        return when {
            c.isEmpty() -> GENERIC
            c.contains("ERROR") -> PRICE_ERROR
            c.contains("USATO") || c.contains("WAREHOUSE") -> USED
            c.startsWith("TV") -> TV
            c.contains("CUFFIE") || c == "AUDIO" -> AUDIO
            c.contains("SMARTPHONE") || c.contains("TABLET") -> SMARTPHONE
            c.contains("SMARTWATCH") || c.contains("WEARABLE") -> SMARTWATCH
            c.contains("NOTEBOOK") || c == "PC" || c.contains("HARDWARE") || c.contains("INFORMATICA") -> PC
            c.contains("GAMING") || c.contains("CONSOLE") -> GAMING
            c.contains("ELETTRODOMESTICI") || c.contains("CASA") -> HOME
            c.contains("ABBIGLIAMENTO") || c.contains("MODA") || c.contains("SCARPE") -> FASHION
            c.contains("BEAUTY") || c.contains("SALUTE") || c.contains("CURA") -> BEAUTY
            c.contains("FAI DA TE") || c == "AUTO" -> DIY
            else -> c
        }
    }

    /** true se l'offerta rientra nel filtro di categoria selezionato. */
    fun matches(offerCategory: String?, filter: String): Boolean {
        if (filter.equals(ALL, ignoreCase = true)) return true
        return canonical(offerCategory) == canonical(filter)
    }
}
