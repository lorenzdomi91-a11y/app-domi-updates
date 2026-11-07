package com.example.offerteamazon.util

/**
 * Log usato dal codice condiviso (app Android e scraper su GitHub).
 * Sul telefono VenatorApplication lo collega ad android.util.Log; nello scraper scrive su console.
 */
object VLog {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: (level: Level, tag: String, message: String, error: Throwable?) -> Unit =
        { level, tag, message, error ->
            if (level >= Level.INFO) {
                println("${level.name.first()}/$tag: $message" + (error?.let { " ($it)" } ?: ""))
            }
        }

    fun v(tag: String, message: String): Int = log(Level.VERBOSE, tag, message, null)
    fun d(tag: String, message: String): Int = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String): Int = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null): Int = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null): Int = log(Level.ERROR, tag, message, error)

    private fun log(level: Level, tag: String, message: String, error: Throwable?): Int {
        try {
            sink(level, tag, message, error)
        } catch (_: Throwable) {
            // il log non deve mai far fallire il chiamante
        }
        return 0
    }
}
