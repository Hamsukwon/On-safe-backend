package com.onsafe.backend.domain.logs

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.logs.service.FallLogService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
class FallLogServiceTest {

    private val fallLogRepository: FallLogRepository = mockk()
    private val storageService: StorageService = mockk()
    private lateinit var fallLogService: FallLogService

    private val baseLog = FallLog(
        logId = "log001",
        deviceId = "device1",
        userId = "testUser",
        score = 85f,
        fall = true,
        isConfirmed = false,
        imageUrl = "gs://bucket/thumb.jpg"
    )

    @BeforeEach
    fun setUp() {
        fallLogService = FallLogService(fallLogRepository, storageService)
    }

    // ── 단건 조회 ─────────────────────────────────────────────────

    @Test
    fun `사고 이력 단건 조회 - 존재하지 않으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.getLog("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 확인 처리 ─────────────────────────────────────────────────

    @Test
    fun `사고 이력 확인 처리 - 존재하지 않으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.confirmByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.confirmLog("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 삭제 ─────────────────────────────────────────────────────

    @Test
    fun `사고 이력 삭제 - 존재하지 않으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.deleteByLogIdAndUserId("log999", "testUser") } returns 0L

        val thrown = runCatching {
            fallLogService.deleteLog("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 썸네일 서명 URL 조회 ──────────────────────────────────────

    @Test
    fun `썸네일 URL 조회 - 로그 없으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.getSignedUrl("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `썸네일 URL 조회 - 이미지 없는 로그면 THUMBNAIL_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser") } returns baseLog.copy(imageUrl = null)

        val thrown = runCatching {
            fallLogService.getSignedUrl("testUser", "log001")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.THUMBNAIL_NOT_FOUND, (thrown as BusinessException).errorCode)
    }
}
