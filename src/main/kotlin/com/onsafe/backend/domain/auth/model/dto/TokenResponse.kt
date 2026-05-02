package com.onsafe.backend.domain.auth.model.dto

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer"
)
