package com.onsafe.backend.domain.settings.model.entity

data class UserSettings(
    val userId: String,
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
)
