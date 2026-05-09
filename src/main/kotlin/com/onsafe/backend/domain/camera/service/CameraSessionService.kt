package com.onsafe.backend.domain.camera.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.dto.CameraSessionResponse
import com.onsafe.backend.domain.camera.model.entity.CameraSessionStatus
import com.onsafe.backend.domain.camera.repository.DeviceRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class CameraSessionService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val deviceRepository: DeviceRepository
) {
    companion object {
        private const val PREFIX = "camera:session:"
        private val TTL = Duration.ofHours(12)
    }

    suspend fun startSession(deviceId: String): CameraSessionResponse {
        val userId = deviceRepository.findUserIdByDeviceId(deviceId)
            ?: throw BusinessException(ErrorCode.DEVICE_NOT_FOUND)
        val now = Instant.now().toString()
        redisTemplate.opsForValue().set("${PREFIX}${userId}:status", CameraSessionStatus.CONNECTING.name, TTL).awaitSingle()
        redisTemplate.opsForValue().set("${PREFIX}${userId}:started_at", now, TTL).awaitSingle()
        return CameraSessionResponse(userId = userId, status = CameraSessionStatus.CONNECTING, startedAt = now)
    }

    suspend fun stopSession(deviceId: String) {
        val userId = deviceRepository.findUserIdByDeviceId(deviceId)
            ?: throw BusinessException(ErrorCode.DEVICE_NOT_FOUND)
        redisTemplate.delete(
            "${PREFIX}${userId}:status",
            "${PREFIX}${userId}:started_at"
        ).awaitSingle()
        // 연결 중인 WebSocket 클라이언트에게 종료 신호 발행
        redisTemplate.convertAndSend("camera:control:$userId", "STOP").awaitSingle()
    }

    suspend fun getSessionStatus(userId: String): CameraSessionResponse {
        val status = redisTemplate.opsForValue().get("${PREFIX}${userId}:status").awaitFirstOrNull()
            ?: CameraSessionStatus.STANDBY.name
        val startedAt = redisTemplate.opsForValue().get("${PREFIX}${userId}:started_at").awaitFirstOrNull()
        val elapsed = startedAt?.let { Duration.between(Instant.parse(it), Instant.now()).seconds }
        return CameraSessionResponse(
            userId = userId,
            status = CameraSessionStatus.valueOf(status),
            startedAt = startedAt,
            elapsedSeconds = elapsed
        )
    }

    // WebSocket 핸들러가 첫 프레임 수신 시 호출 → CONNECTING → LIVE
    suspend fun markLive(userId: String) {
        redisTemplate.opsForValue().set("${PREFIX}${userId}:status", CameraSessionStatus.LIVE.name, TTL).awaitSingle()
    }

    // WebSocket 연결 종료 시 호출 → LIVE → STANDBY (세션 키 보존, 상태만 변경)
    suspend fun markStandby(userId: String) {
        redisTemplate.opsForValue().set("${PREFIX}${userId}:status", CameraSessionStatus.STANDBY.name, TTL).awaitSingle()
    }
}
