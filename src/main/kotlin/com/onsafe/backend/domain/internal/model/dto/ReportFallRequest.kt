package com.onsafe.backend.domain.internal.model.dto

data class ReportFallRequest(
    val userId: String,
    val confidence: Float
)
