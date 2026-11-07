package com.example.offerteamazon.util

import com.example.offerteamazon.util.VLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

suspend fun <T> retryWithExponentialBackoff(
    times: Int = 3,
    initialDelayMs: Long = 1000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    repeat(times - 1) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VENATOR", "Network attempt ${attempt + 1} failed: ${e.message}. Retrying in ${currentDelay}ms...")
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong()
        }
    }
    return block()
}
