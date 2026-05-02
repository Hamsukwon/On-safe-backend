package com.onsafe.backend.domain.settings.model.dto

data class NotificationSettingsRequest(
    val notificationEnabled: Boolean? = null,
    val fallSensitivity: String? = null
)
