package com.onsafe.backend.domain.internal

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.internal.model.dto.SaveFallLogRequest
import com.onsafe.backend.domain.internal.service.InternalService
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.service.NotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

class InternalServiceTest {

    private val realtimeDataRepository: RealtimeDataRepository = mockk(relaxed = true)
    private val fallLogRepository: FallLogRepository = mockk()
    private val notificationService: NotificationService = mockk()
    private val redisTemplate: ReactiveStringRedisTemplate = mockk(relaxed = true)
    private lateinit var internalService: InternalService

    private val baseRequest = SaveFallLogRequest(
        logId = "log001",
        deviceId = "device1",
        userId = "testUser",
        score = 90f,
        fall = true,
        isConfirmed = false,
        videoUrl = null
    )

    @BeforeEach
    fun setUp() {
        internalService = InternalService(realtimeDataRepository, fallLogRepository, notificationService, redisTemplate)
    }

    // ── DB 저장 보장 ──────────────────────────────────────────────

    @Test
    fun `FCM 전송 실패해도 DB 저장은 완료된다`() = runTest {
        val savedSlot = slot<FallLog>()
        coEvery { fallLogRepository.save(capture(savedSlot)) } answers { firstArg() }
        coEvery { notificationService.sendNotification(any()) } throws BusinessException(ErrorCode.FCM_SEND_FAILED)

        // 예외 없이 정상 종료되어야 함
        internalService.saveFallLog(baseRequest)

        assertEquals("log001", savedSlot.captured.logId)
        assertEquals("testUser", savedSlot.captured.userId)
        assertEquals(90f, savedSlot.captured.score)
    }

    // ── 알림 발송 조건 ────────────────────────────────────────────

    @Test
    fun `낙상 감지(fall=true) 시 낙상 경보 알림 발송`() = runTest {
        val notifSlot = slot<NotificationRequest>()
        coEvery { fallLogRepository.save(any()) } answers { firstArg() }
        coEvery { notificationService.sendNotification(capture(notifSlot)) } returns mockk(relaxed = true)

        internalService.saveFallLog(baseRequest.copy(fall = true, score = 50f))

        assertEquals("낙상 감지 경보", notifSlot.captured.title)
        assertEquals("testUser", notifSlot.captured.userId)
    }

    @Test
    fun `위험 점수(75 초과, fall=false) 시 위험 수준 알림 발송`() = runTest {
        val notifSlot = slot<NotificationRequest>()
        coEvery { fallLogRepository.save(any()) } answers { firstArg() }
        coEvery { notificationService.sendNotification(capture(notifSlot)) } returns mockk(relaxed = true)

        internalService.saveFallLog(baseRequest.copy(fall = false, score = 80f))

        assertEquals("위험 수준 감지", notifSlot.captured.title)
    }

    @Test
    fun `주의 점수(50 초과 75 이하) 시 주의 수준 알림 발송`() = runTest {
        val notifSlot = slot<NotificationRequest>()
        coEvery { fallLogRepository.save(any()) } answers { firstArg() }
        coEvery { notificationService.sendNotification(capture(notifSlot)) } returns mockk(relaxed = true)

        internalService.saveFallLog(baseRequest.copy(fall = false, score = 60f))

        assertEquals("주의 수준 감지", notifSlot.captured.title)
    }

    @Test
    fun `정상 점수(50 이하, fall=false) 시 알림 미발송`() = runTest {
        coEvery { fallLogRepository.save(any()) } answers { firstArg() }

        internalService.saveFallLog(baseRequest.copy(fall = false, score = 30f))

        coVerify(exactly = 0) { notificationService.sendNotification(any()) }
    }

    // ── 알림 데이터 검증 ──────────────────────────────────────────

    @Test
    fun `알림 data에 log_id, user_id, score가 포함된다`() = runTest {
        val notifSlot = slot<NotificationRequest>()
        coEvery { fallLogRepository.save(any()) } answers { firstArg() }
        coEvery { notificationService.sendNotification(capture(notifSlot)) } returns mockk(relaxed = true)

        internalService.saveFallLog(baseRequest)

        val data = notifSlot.captured.data!!
        assertEquals("log001", data["log_id"])
        assertEquals("testUser", data["user_id"])
        assertEquals("90.0", data["score"])
    }

    // ── isConfirmed 기본값 검증 ───────────────────────────────────

    @Test
    fun `isConfirmed 기본값은 false로 저장된다`() = runTest {
        val savedSlot = slot<FallLog>()
        coEvery { fallLogRepository.save(capture(savedSlot)) } answers { firstArg() }
        coEvery { notificationService.sendNotification(any()) } returns mockk(relaxed = true)

        internalService.saveFallLog(baseRequest.copy(isConfirmed = false))

        assertFalse(savedSlot.captured.isConfirmed)
    }
}
