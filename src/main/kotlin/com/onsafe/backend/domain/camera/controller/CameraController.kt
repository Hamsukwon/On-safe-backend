package com.onsafe.backend.domain.camera.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.camera.model.dto.CameraUrlRequest
import com.onsafe.backend.domain.camera.model.dto.RiskScoreResponse
import com.onsafe.backend.domain.camera.model.dto.RiskStatusResponse
import com.onsafe.backend.domain.camera.service.CameraService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "Camera", description = "카메라 & 실시간 모니터링 API")
@RestController
@RequestMapping("/api/camera")
class CameraController(private val cameraService: CameraService) {

    @Operation(summary = "현재 위험 점수 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/score/{userId}")
    suspend fun getRiskScore(@PathVariable userId: String): ApiResponse<RiskScoreResponse> {
        return ApiResponse.ok(cameraService.getRiskScore(userId))
    }

    @Operation(summary = "현재 위험 상태 조회 (정상/주의/위험)", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/status/{userId}")
    suspend fun getRiskStatus(@PathVariable userId: String): ApiResponse<RiskStatusResponse> {
        return ApiResponse.ok(cameraService.getRiskStatus(userId))
    }

    @Operation(summary = "실시간 영상 스트림 URL 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/stream/{userId}")
    suspend fun getStreamUrl(@PathVariable userId: String): ApiResponse<Map<String, String>> {
        val url = cameraService.getCameraUrl(userId)
        return ApiResponse.ok(mapOf("streamUrl" to url))
    }

    @Operation(summary = "카메라 URL 등록/수정", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/url/{deviceId}")
    suspend fun updateCameraUrl(
        @PathVariable deviceId: String,
        @Valid @RequestBody request: CameraUrlRequest
    ): ApiResponse<Unit> {
        cameraService.updateCameraUrl(deviceId, request)
        return ApiResponse.ok(message = "카메라 URL이 업데이트되었습니다.")
    }
}
