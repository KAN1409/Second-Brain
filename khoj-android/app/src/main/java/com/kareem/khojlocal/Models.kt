package com.kareem.khojlocal

data class Memory(
    val id: Long,
    val title: String,
    val body: String,
    val source: String,
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
    val attachmentPath: String? = null,
)

data class SearchHit(
    val memory: Memory,
    val score: Double,
    val lexicalScore: Double,
    val vectorScore: Double,
)
