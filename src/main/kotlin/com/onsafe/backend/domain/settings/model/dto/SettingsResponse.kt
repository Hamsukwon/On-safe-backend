package com.onsafe.backend.domain.settings.model.dto

import com.onsafe.backend.domain.settings.model.entity.UserSettings

data class NotificationSettingsResponse(
    val notificationEnabled: Boolean,
    val fallSensitivity: String
) {
    companion object {
        fun from(s: UserSettings) = NotificationSettingsResponse(
            notificationEnabled = s.notificationEnabled,
            fallSensitivity = s.fallSensitivity
        )
    }
}

data class RetentionSettingsResponse(
    val retentionDays: Int
) {
    companion object {
        fun from(s: UserSettings) = RetentionSettingsResponse(retentionDays = s.retentionDays)
    }
}
