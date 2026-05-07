package com.onsafe.backend.domain.internal.service

import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.internal.model.dto.SaveFallLogRequest
import com.onsafe.backend.domain.internal.model.dto.UpdateRealtimeRequest
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.service.NotificationService
import org.springframework.stereotype.Service

@Service
class InternalService(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val fallLogRepository: FallLogRepository,
    private val notificationService: NotificationService
) {

    suspend fun updateRealtime(req: UpdateRealtimeRequest) {
        val existing = realtimeDataRepository.findByUserId(req.userId)
        val data = existing?.copy(score = req.score, level = req.level)
            ?: RealtimeData(userId = req.userId, score = req.score, level = req.level)
        realtimeDataRepository.save(data)
    }

    suspend fun saveFallLog(req: SaveFallLogRequest) {
        fallLogRepository.save(
            FallLog(
                logId = req.logId,
                deviceId = req.deviceId,
                userId = req.userId,
                score = req.score,
                fall = req.fall,
                isConfirmed = req.isConfirmed
            )
        )
        if (req.fall) {
            notificationService.sendNotification(
                NotificationRequest(
                    userId = req.userId,
                    title = "낙상 감지 경보",
                    body = "낙상이 감지되었습니다. 즉시 확인하세요.",
                    data = mapOf("score" to req.score.toString())
                )
            )
        }
    }
}
