package com.onsafe.backend.domain.camera.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.dto.CameraUrlRequest
import com.onsafe.backend.domain.camera.model.dto.DeviceResponse
import com.onsafe.backend.domain.camera.model.dto.RiskScoreResponse
import com.onsafe.backend.domain.camera.model.dto.RiskStatusResponse
import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import com.onsafe.backend.domain.camera.repository.DeviceRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import org.springframework.stereotype.Service

@Service
class CameraService(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val deviceRepository: DeviceRepository
) {

    suspend fun getRiskScore(userId: String): RiskScoreResponse {
        val data = realtimeDataRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.REALTIME_DATA_NOT_FOUND)

        return RiskScoreResponse(
            userId = userId,
            score = data.score,
            level = data.level,
            updatedAt = data.updatedAt
        )
    }

    suspend fun getRiskStatus(userId: String): RiskStatusResponse {
        val data = realtimeDataRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.REALTIME_DATA_NOT_FOUND)
        val risk = RiskLevel.fromLabel(data.level)
        return RiskStatusResponse(
            userId = userId,
            level = risk.label,
            score = data.score,
            colorCode = risk.colorCode
        )
    }

    suspend fun getDevices(userId: String): List<DeviceResponse> =
        deviceRepository.findAllByUserId(userId)

    suspend fun getCameraUrl(userId: String): String =
        deviceRepository.findCameraUrlByUserId(userId)
            ?: throw BusinessException(ErrorCode.CAMERA_NOT_FOUND)

    suspend fun updateCameraUrl(deviceId: String, request: CameraUrlRequest, requesterId: String) {
        val ownerId = deviceRepository.findUserIdByDeviceId(deviceId)
            ?: throw BusinessException(ErrorCode.DEVICE_NOT_FOUND)
        if (ownerId != requesterId) throw BusinessException(ErrorCode.FORBIDDEN)
        deviceRepository.updateCameraUrl(deviceId, request.cameraUrl)
    }

    companion object {
        fun calculateLevel(score: Float) = RiskLevel.fromScore(score).label
    }
}
