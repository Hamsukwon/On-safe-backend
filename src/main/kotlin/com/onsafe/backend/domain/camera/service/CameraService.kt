package com.onsafe.backend.domain.camera.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.dto.CameraUrlRequest
import com.onsafe.backend.domain.camera.model.dto.RiskScoreResponse
import com.onsafe.backend.domain.camera.model.dto.RiskStatusResponse
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
            level = data.level
        )
    }

    suspend fun getRiskStatus(userId: String): RiskStatusResponse {
        val data = realtimeDataRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.REALTIME_DATA_NOT_FOUND)

        return RiskStatusResponse(
            userId = userId,
            level = data.level,
            score = data.score,
            colorCode = colorCodeOf(data.level)
        )
    }

    suspend fun getCameraUrl(userId: String): String =
        deviceRepository.findCameraUrlByUserId(userId)
            ?: throw BusinessException(ErrorCode.CAMERA_NOT_FOUND)

    suspend fun updateCameraUrl(deviceId: String, request: CameraUrlRequest) =
        deviceRepository.updateCameraUrl(deviceId, request.cameraUrl)

    private fun colorCodeOf(level: String) = when (level.lowercase()) {
        "위험", "danger", "high" -> "#FF0000"
        "주의", "warning", "medium" -> "#FFA500"
        else -> "#00C853"
    }

    companion object {
        fun calculateLevel(score: Float) = when {
            score > 0.7f -> "위험"
            score > 0.4f -> "주의"
            else         -> "정상"
        }
    }
}
