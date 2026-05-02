package com.onsafe.backend.domain.settings.model.entity

data class UserSettings(
    val userId: String,
    val notificationEnabled: Boolean = true,
    val fallSensitivity: String = "medium",
    val retentionDays: Int = 30
)
