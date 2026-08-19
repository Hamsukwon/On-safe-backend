package com.onsafe.backend.domain.user.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
    private val settingsRepository: SettingsRepository,
    private val passwordEncoder: PasswordEncoder,
    private val fallLogRepository: FallLogRepository,
    private val loginHistoryRepository: LoginHistoryRepository,
    private val realtimeDataRepository: RealtimeDataRepository,
    private val storageService: StorageService,
    private val notificationRepository: NotificationRepository,
    private val guardianLinkRepository: GuardianLinkRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getUser(userId: String): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return UserResponse.from(user)
    }

    suspend fun updateUser(userId: String, request: UserUpdateRequest): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (request.password != null) {
            if (request.currentPassword == null || !passwordEncoder.matches(request.currentPassword, user.password)) {
                throw BusinessException(ErrorCode.INVALID_PASSWORD)
            }
        }
        val updated = user.copy(
            name = request.name ?: user.name,
            password = if (request.password != null) passwordEncoder.encode(request.password) else user.password,
            mail = request.mail ?: user.mail,
            phone = request.phone ?: user.phone,
            address = request.address ?: user.address,
            addressDetail = request.addressDetail ?: user.addressDetail
        )
        return UserResponse.from(userRepository.save(updated))
    }

    suspend fun verifyPassword(userId: String, currentPassword: String) {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (!passwordEncoder.matches(currentPassword, user.password)) {
            throw BusinessException(ErrorCode.INVALID_PASSWORD)
        }
    }

    suspend fun deleteUser(userId: String) {
        if (!userRepository.existsByUserId(userId)) {
            throw BusinessException(ErrorCode.USER_NOT_FOUND)
        }
        // 개인정보보호법 제21조: 회원탈퇴 시 지체 없이 파기. Firestore 문서만 지우면
        // GCS 라이프사이클(gcs-lifecycle.json 상 최대 180일)까지 원본 영상이 남으므로
        // logId를 먼저 수집해 blob을 삭제한 뒤 Firestore를 지운다.
        // blob 삭제 실패는 계정 삭제를 막지 않는다(사용자가 "탈퇴가 안 된다" 상태에 갇히지 않도록) —
        // 개별 실패는 warn 로그로 남겨 사후 파기 재시도가 가능하도록 한다.
        val logIds = fallLogRepository.findLogIdsByUserId(userId)
        logIds.forEach { logId ->
            runCatching { storageService.deleteBlob("fall-videos/$logId.mp4") }
                .onFailure { e ->
                    log.warn(
                        "fall-video blob 삭제 실패 — userId={}, logId={}, cause={}",
                        userId, logId, e.javaClass.simpleName
                    )
                }
        }
        fallLogRepository.deleteByUserId(userId)
        realtimeDataRepository.deleteByUserId(userId)
        loginHistoryRepository.deleteByUserId(userId)
        settingsRepository.deleteByUserId(userId)
        notificationRepository.deleteByUserId(userId)
        guardianLinkRepository.deleteAllInvolving(userId)
        userRepository.deleteByUserId(userId)
    }
}
