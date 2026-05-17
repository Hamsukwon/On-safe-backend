package com.onsafe.backend.domain.camera.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.camera.model.dto.DeviceResponse
import com.onsafe.backend.domain.camera.service.CameraService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Devices", description = "기기 관리 API")
@RestController
@RequestMapping("/api/devices")
class DeviceController(private val cameraService: CameraService) {

    @Operation(summary = "기기 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}")
    suspend fun getDevices(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Map<String, List<DeviceResponse>>> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(mapOf("devices" to cameraService.getDevices(userId)))
    }
}
