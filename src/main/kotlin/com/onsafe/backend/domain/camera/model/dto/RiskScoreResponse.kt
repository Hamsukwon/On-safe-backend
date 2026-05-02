package com.onsafe.backend.domain.camera.model.dto

data class RiskScoreResponse(
    val userId: String,
    val score: Float,
    val level: String
)
