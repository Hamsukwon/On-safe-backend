package com.onsafe.backend.domain.logs.model.dto

import com.onsafe.backend.domain.logs.model.entity.DetectionLog
import java.time.LocalDateTime

data class DetectionLogResponse(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean,
    val timestamp: LocalDateTime
) {
    companion object {
        fun from(log: DetectionLog) = DetectionLogResponse(
            logId = log.logId,
            deviceId = log.deviceId,
            userId = log.userId,
            score = log.score,
            fall = log.fall,
            isConfirmed = log.isConfirmed,
            timestamp = log.timestamp
        )
    }
}
