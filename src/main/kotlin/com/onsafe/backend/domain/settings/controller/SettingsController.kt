package com.onsafe.backend.domain.settings.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsResponse
import com.onsafe.backend.domain.settings.model.dto.RetentionSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.RetentionSettingsResponse
import com.onsafe.backend.domain.settings.service.SettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "Settings", description = "설정 API")
@RestController
@RequestMapping("/api/settings")
class SettingsController(private val settingsService: SettingsService) {

    @Operation(summary = "알림 설정 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/notifications/{userId}")
    suspend fun getNotificationSettings(@PathVariable userId: String): ApiResponse<NotificationSettingsResponse> {
        return ApiResponse.ok(settingsService.getNotificationSettings(userId))
    }

    @Operation(summary = "알림 설정 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/notifications/{userId}")
    suspend fun updateNotifications(
        @PathVariable userId: String,
        @RequestBody request: NotificationSettingsRequest
    ): ApiResponse<Unit> {
        settingsService.updateNotifications(userId, request)
        return ApiResponse.ok(message = "알림 설정 변경 완료")
    }

    @Operation(summary = "로그 보관 기간 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/retention/{userId}")
    suspend fun getRetentionSettings(@PathVariable userId: String): ApiResponse<RetentionSettingsResponse> {
        return ApiResponse.ok(settingsService.getRetentionSettings(userId))
    }

    @Operation(summary = "로그 보관 기간 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/retention/{userId}")
    suspend fun updateRetention(
        @PathVariable userId: String,
        @Valid @RequestBody request: RetentionSettingsRequest
    ): ApiResponse<RetentionSettingsResponse> {
        return ApiResponse.ok(settingsService.updateRetention(userId, request), "영상 보관 기간이 설정되었습니다.")
    }
}
