package com.onsafe.backend.domain.settings.model.dto

import com.onsafe.backend.domain.settings.model.entity.UserSettings
import java.time.LocalDateTime

data class NotificationSettingsResponse(
    val notificationEnabled: Boolean,
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
) {
    companion object {
        fun from(s: UserSettings) = NotificationSettingsResponse(
            notificationEnabled = s.notificationEnabled,
            soundEnabled = s.soundEnabled,
            vibrationEnabled = s.vibrationEnabled,
        )
    }
}

data class RetentionSettingsResponse(val retentionDays: Int = 30)

data class MarketingConsentResponse(
    val consent: Boolean,
    val consentedAt: LocalDateTime?,
) {
    companion object {
        fun from(s: UserSettings) = MarketingConsentResponse(
            consent = s.marketingConsent,
            consentedAt = s.marketingConsentedAt,
        )
    }
}
