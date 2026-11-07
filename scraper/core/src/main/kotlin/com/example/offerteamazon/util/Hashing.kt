package com.example.offerteamazon.util

import java.security.MessageDigest

object Hashing {
    /** SHA-256 esadecimale di "venator:" + valore (stesso formato usato in config.json). */
    fun venatorHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("venator:$value".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
