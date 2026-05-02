package com.onsafe.backend.domain.internal.service

import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.fall.model.entity.FallEvent
import com.onsafe.backend.domain.fall.repository.FallEventRepository
import com.onsafe.backend.domain.internal.model.dto.ReportFallRequest
import com.onsafe.backend.domain.internal.model.dto.SaveDetectionLogRequest
import com.onsafe.backend.domain.internal.model.dto.UpdateRealtimeRequest
import com.onsafe.backend.domain.logs.model.entity.DetectionLog
import com.onsafe.backend.domain.logs.repository.DetectionLogRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.service.NotificationService
import org.springframework.stereotype.Service

@Service
class InternalService(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val fallEventRepository: FallEventRepository,
    private val detectionLogRepository: DetectionLogRepository,
    private val notificationService: NotificationService
) {

    suspend fun updateRealtime(req: UpdateRealtimeRequest) {
        val existing = realtimeDataRepository.findByUserId(req.userId)
        val data = existing?.copy(score = req.score, level = req.level)
            ?: RealtimeData(userId = req.userId, score = req.score, level = req.level)
        realtimeDataRepository.save(data)
    }

    suspend fun reportFall(req: ReportFallRequest) {
        fallEventRepository.save(FallEvent(userId = req.userId, confidence = req.confidence))
        notificationService.sendNotification(
            NotificationRequest(
                userId = req.userId,
                title = "낙상 감지 경보",
                body = "낙상이 감지되었습니다. 즉시 확인하세요.",
                data = mapOf("score" to req.confidence.toString())
            )
        )
    }

    suspend fun saveDetectionLog(req: SaveDetectionLogRequest) {
        detectionLogRepository.save(
            DetectionLog(
                logId = req.logId,
                deviceId = req.deviceId,
                userId = req.userId,
                score = req.score,
                fall = req.fall,
                isConfirmed = req.isConfirmed
            )
        )
    }
}
