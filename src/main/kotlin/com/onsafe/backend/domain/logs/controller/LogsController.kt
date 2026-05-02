package com.onsafe.backend.domain.logs.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.logs.model.dto.DetectionLogResponse
import com.onsafe.backend.domain.logs.service.LogsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@Tag(name = "Logs", description = "사고 이력 API")
@RestController
@RequestMapping("/api/logs")
class LogsController(private val logsService: LogsService) {

    @Operation(summary = "사고 이력 목록 조회 (최근 30일)", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}")
    suspend fun getLogs(@PathVariable userId: String): ApiResponse<Map<String, List<DetectionLogResponse>>> {
        val logs = logsService.getLogs(userId)
        return ApiResponse.ok(mapOf("logs" to logs))
    }

    @Operation(summary = "사고 이력 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}/{logId}")
    suspend fun getLog(
        @PathVariable userId: String,
        @PathVariable logId: String
    ): ApiResponse<DetectionLogResponse> {
        return ApiResponse.ok(logsService.getLog(userId, logId))
    }

    @Operation(summary = "사고 이력 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{userId}/{logId}")
    suspend fun deleteLog(
        @PathVariable userId: String,
        @PathVariable logId: String
    ): ApiResponse<Unit> {
        logsService.deleteLog(userId, logId)
        return ApiResponse.ok(message = "사고 이력이 삭제되었습니다.")
    }
}
