package com.onsafe.backend.domain.fall.model.dto

import com.onsafe.backend.domain.fall.model.entity.FallEvent
import java.time.LocalDateTime

data class FallEventResponse(
    val id: String,
    val userId: String,
    val confidence: Float,
    val mediaUrl: String?,
    val isConfirmed: Boolean,
    val detectedAt: LocalDateTime
) {
    companion object {
        fun from(event: FallEvent) = FallEventResponse(
            id = event.id,
            userId = event.userId,
            confidence = event.confidence,
            mediaUrl = event.mediaUrl,
            isConfirmed = event.isConfirmed,
            detectedAt = event.detectedAt
        )
    }
}
