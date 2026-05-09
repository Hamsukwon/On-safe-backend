package com.onsafe.backend.domain.internal.service

import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.internal.model.dto.SaveFallLogRequest
import com.onsafe.backend.domain.internal.model.dto.UpdateRealtimeRequest
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.service.NotificationService
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.util.Base64

@Service
class InternalService(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val fallLogRepository: FallLogRepository,
    private val notificationService: NotificationService,
    private val redisTemplate: ReactiveStringRedisTemplate
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

    // JPEG 바이트를 Base64로 인코딩 후 Redis pub/sub으로 발행
    // WebSocket 핸들러(CameraStreamWebSocketHandler)가 구독해 클라이언트에 전달
    suspend fun publishFrame(userId: String, frame: ByteArray) {
        val base64 = Base64.getEncoder().encodeToString(frame)
        redisTemplate.convertAndSend("camera:frames:$userId", base64).awaitSingle()
    }
}
