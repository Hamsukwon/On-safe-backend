package com.onsafe.backend.domain.settings.model.entity

import java.time.LocalDateTime

data class UserSettings(
    val userId: String,
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val marketingConsent: Boolean = false,
    val marketingConsentedAt: LocalDateTime? = null,
)
