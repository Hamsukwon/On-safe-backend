package com.onsafe.backend.domain.settings.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsResponse
import com.onsafe.backend.domain.settings.model.dto.RetentionSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.RetentionSettingsResponse
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class SettingsService(
    private val settingsRepository: SettingsRepository,
    private val userRepository: UserRepository
) {

    suspend fun getNotificationSettings(userId: String): NotificationSettingsResponse =
        NotificationSettingsResponse.from(getOrCreateSettings(userId))

    suspend fun updateNotifications(userId: String, request: NotificationSettingsRequest): NotificationSettingsResponse {
        val settings = getOrCreateSettings(userId)
        return NotificationSettingsResponse.from(
            settingsRepository.save(
                settings.copy(
                    notificationEnabled = request.notificationEnabled ?: settings.notificationEnabled,
                    soundEnabled = request.soundEnabled ?: settings.soundEnabled,
                    vibrationEnabled = request.vibrationEnabled ?: settings.vibrationEnabled,
                    fallSensitivity = request.fallSensitivity ?: settings.fallSensitivity
                )
            )
        )
    }

    suspend fun getRetentionSettings(userId: String): RetentionSettingsResponse =
        RetentionSettingsResponse.from(getOrCreateSettings(userId))

    suspend fun updateRetention(userId: String, request: RetentionSettingsRequest): RetentionSettingsResponse =
        RetentionSettingsResponse.from(
            settingsRepository.save(getOrCreateSettings(userId).copy(retentionDays = request.retentionDays))
        )

    private suspend fun getOrCreateSettings(userId: String): UserSettings =
        settingsRepository.findByUserId(userId)
            ?: if (userRepository.existsByUserId(userId))
                settingsRepository.save(UserSettings(userId = userId))
               else
                throw BusinessException(ErrorCode.USER_NOT_FOUND)
}
