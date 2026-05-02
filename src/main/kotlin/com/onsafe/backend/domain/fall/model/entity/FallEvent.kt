package com.onsafe.backend.domain.fall.model.entity

import java.time.LocalDateTime
import java.util.UUID

data class FallEvent(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val confidence: Float,
    val mediaUrl: String? = null,
    val isConfirmed: Boolean = false,
    val detectedAt: LocalDateTime = LocalDateTime.now()
)
