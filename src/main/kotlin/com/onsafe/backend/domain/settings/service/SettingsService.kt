package com.onsafe.backend.domain.settings.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsResponse
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
                )
            )
        )
    }

    // 유저 존재 여부를 검증하지 않고 기본값 반환 — 신규 사용자도 설정 화면 진입 가능하도록 의도적으로 생략
    suspend fun getRetentionSettings(userId: String): RetentionSettingsResponse = RetentionSettingsResponse()

    private suspend fun getOrCreateSettings(userId: String): UserSettings =
        settingsRepository.findByUserId(userId)
            ?: if (userRepository.existsByUserId(userId))
                settingsRepository.save(UserSettings(userId = userId))
               else
                throw BusinessException(ErrorCode.USER_NOT_FOUND)
}
