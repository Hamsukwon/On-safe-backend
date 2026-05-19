package com.onsafe.backend.domain.camera.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.camera.model.dto.CameraSessionResponse
import com.onsafe.backend.domain.camera.service.CameraSessionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@Tag(name = "Camera Session", description = "카메라 세션 상태 관리 API (촬영 시작/종료/조회)")
@RestController
@RequestMapping("/api/camera/session")
class CameraSessionController(private val cameraSessionService: CameraSessionService) {

    @Operation(
        summary = "카메라 촬영 시작",
        description = "기기가 촬영을 시작할 때 호출. 상태를 CONNECTING으로 변경하고 시작 시각을 기록합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PutMapping("/{deviceId}/start")
    suspend fun startSession(@PathVariable deviceId: String): ApiResponse<CameraSessionResponse> =
        ApiResponse.ok(cameraSessionService.startSession(deviceId))

    @Operation(
        summary = "카메라 촬영 종료",
        description = "기기가 촬영을 종료할 때 호출. 세션 정보를 삭제하고 상태를 STANDBY로 되돌립니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PutMapping("/{deviceId}/stop")
    suspend fun stopSession(@PathVariable deviceId: String): ApiResponse<Unit> {
        cameraSessionService.stopSession(deviceId)
        return ApiResponse.ok(message = "촬영이 종료되었습니다.")
    }

    @Operation(
        summary = "현재 세션 상태 조회",
        description = "클라이언트가 폴링으로 세션 상태(STANDBY/CONNECTING/LIVE)를 확인합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}/status")
    suspend fun getSessionStatus(@PathVariable userId: String): ApiResponse<CameraSessionResponse> =
        ApiResponse.ok(cameraSessionService.getSessionStatus(userId))
}
