package com.onsafe.backend.domain.notification.model.dto

import jakarta.validation.constraints.NotBlank

data class NotificationRequest(
    @field:NotBlank val userId: String,
    @field:NotBlank val title: String,
    @field:NotBlank val body: String,
    val data: Map<String, String>? = null
)
