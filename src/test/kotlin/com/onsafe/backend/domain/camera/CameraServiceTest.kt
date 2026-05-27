package com.onsafe.backend.domain.camera

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.dto.CameraUrlRequest
import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.DeviceRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.camera.service.CameraService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CameraServiceTest {

    private val realtimeDataRepository: RealtimeDataRepository = mockk()
    private val deviceRepository: DeviceRepository = mockk()
    private lateinit var cameraService: CameraService

    private val baseRealtimeData = RealtimeData(userId = "testUser", score = 30f, level = "정상")

    @BeforeEach
    fun setUp() {
        cameraService = CameraService(realtimeDataRepository, deviceRepository)
    }

    // ── 위험도 점수 조회 ──────────────────────────────────────────

    @Test
    fun `위험도 점수 조회 - 실시간 데이터 없으면 REALTIME_DATA_NOT_FOUND 예외 발생`() = runTest {
        coEvery { realtimeDataRepository.findByUserId("testUser") } returns null

        val thrown = runCatching {
            cameraService.getRiskScore("testUser")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.REALTIME_DATA_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 위험도 상태 조회 ──────────────────────────────────────────

    @Test
    fun `위험도 상태 조회 - 실시간 데이터 없으면 REALTIME_DATA_NOT_FOUND 예외 발생`() = runTest {
        coEvery { realtimeDataRepository.findByUserId("testUser") } returns null

        val thrown = runCatching {
            cameraService.getRiskStatus("testUser")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.REALTIME_DATA_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 카메라 URL 조회 ───────────────────────────────────────────

    @Test
    fun `카메라 URL 조회 - 카메라 없으면 CAMERA_NOT_FOUND 예외 발생`() = runTest {
        coEvery { deviceRepository.findCameraUrlByUserId("testUser") } returns null

        val thrown = runCatching {
            cameraService.getCameraUrl("testUser")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.CAMERA_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 카메라 URL 수정 ───────────────────────────────────────────

    @Test
    fun `카메라 URL 수정 - 기기 미존재 시 DEVICE_NOT_FOUND 예외 발생`() = runTest {
        coEvery { deviceRepository.findUserIdByDeviceId("device999") } returns null

        val thrown = runCatching {
            cameraService.updateCameraUrl("device999", CameraUrlRequest(cameraUrl = "rtsp://new"), "testUser")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `카메라 URL 수정 - 기기 소유자가 아니면 FORBIDDEN 예외 발생`() = runTest {
        coEvery { deviceRepository.findUserIdByDeviceId("device1") } returns "ownerUser"

        val thrown = runCatching {
            cameraService.updateCameraUrl("device1", CameraUrlRequest(cameraUrl = "rtsp://new"), "attackerUser")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.FORBIDDEN, (thrown as BusinessException).errorCode)
    }
}
