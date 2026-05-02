package com.onsafe.backend.domain.settings.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsResponse
import com.onsafe.backend.domain.settings.model.dto.RetentionSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.RetentionSettingsResponse
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import org.springframework.stereotype.Service

@Service
class SettingsService(private val settingsRepository: SettingsRepository) {

    suspend fun getNotificationSettings(userId: String): NotificationSettingsResponse {
        val settings = settingsRepository.findByUserId(userId)
            ?: settingsRepository.save(UserSettings(userId = userId))
        return NotificationSettingsResponse.from(settings)
    }

    suspend fun updateNotifications(userId: String, request: NotificationSettingsRequest): NotificationSettingsResponse {
        val settings = settingsRepository.findByUserId(userId) ?: UserSettings(userId = userId)
        return NotificationSettingsResponse.from(
            settingsRepository.save(
                settings.copy(
                    notificationEnabled = request.notificationEnabled ?: settings.notificationEnabled,
                    fallSensitivity = request.fallSensitivity ?: settings.fallSensitivity
                )
            )
        )
    }

    suspend fun getRetentionSettings(userId: String): RetentionSettingsResponse {
        val settings = settingsRepository.findByUserId(userId)
            ?: settingsRepository.save(UserSettings(userId = userId))
        return RetentionSettingsResponse.from(settings)
    }

    suspend fun updateRetention(userId: String, request: RetentionSettingsRequest): RetentionSettingsResponse {
        val settings = settingsRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.SETTINGS_NOT_FOUND)
        return RetentionSettingsResponse.from(
            settingsRepository.save(settings.copy(retentionDays = request.retentionDays))
        )
    }
}
