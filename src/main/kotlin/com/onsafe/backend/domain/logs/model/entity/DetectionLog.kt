package com.onsafe.backend.domain.logs.model.entity

import java.time.LocalDateTime

data class DetectionLog(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean = false,
    val timestamp: LocalDateTime = LocalDateTime.now()
)
