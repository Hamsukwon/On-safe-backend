package com.onsafe.backend.domain.notification.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.model.dto.NotificationResponse
import com.onsafe.backend.domain.notification.service.NotificationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "Notification", description = "FCM 푸시 알림 API")
@RestController
@RequestMapping("/api/notification")
class NotificationController(private val notificationService: NotificationService) {

    @Operation(summary = "위험 감지 시 FCM 푸시 알림 발송")
    @PostMapping("/send")
    suspend fun send(@Valid @RequestBody request: NotificationRequest): ApiResponse<NotificationResponse> {
        val result = notificationService.sendNotification(request)
        return ApiResponse.ok(result)
    }
}
