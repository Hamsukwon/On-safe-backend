package com.onsafe.backend.domain.auth.model.dto

data class LoginResponse(
    val userId: String,
    val deviceId: String,
    val name: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer"
)
