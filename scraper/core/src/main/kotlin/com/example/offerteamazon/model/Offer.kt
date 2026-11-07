package com.example.offerteamazon.model

data class Offer(
    val id: String,
    val title: String,
    val description: String,
    val originalPrice: String?,
    val discountedPrice: String,
    val discountPercentage: Int?,
    val imageUrl: String,
    val amazonUrl: String,
    val source: String, // e.g., "Telegram - Offerte Più", "GitHub List"
    val category: String? = null,
    val couponCode: String? = null,
    val isFavorite: Boolean = false,
    val dealScore: Int = 0, // Punteggio AI da 0 a 100
    val aiInsight: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
