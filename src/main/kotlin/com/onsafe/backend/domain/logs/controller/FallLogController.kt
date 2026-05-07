package com.onsafe.backend.domain.logs.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.logs.model.dto.FallLogResponse
import com.onsafe.backend.domain.logs.service.FallLogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@Tag(name = "FallLogs", description = "낙상 로그 API")
@RestController
@RequestMapping("/api/fall-logs")
class FallLogController(private val fallLogService: FallLogService) {

    @Operation(summary = "낙상 로그 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}")
    suspend fun getLogs(@PathVariable userId: String): ApiResponse<Map<String, List<FallLogResponse>>> =
        ApiResponse.ok(mapOf("logs" to fallLogService.getLogs(userId)))

    @Operation(summary = "낙상 로그 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}/{logId}")
    suspend fun getLog(
        @PathVariable userId: String,
        @PathVariable logId: String
    ): ApiResponse<FallLogResponse> =
        ApiResponse.ok(fallLogService.getLog(userId, logId))

    @Operation(summary = "낙상 이벤트 확인 처리", security = [SecurityRequirement(name = "BearerAuth")])
    @PatchMapping("/{userId}/{logId}/confirm")
    suspend fun confirmLog(
        @PathVariable userId: String,
        @PathVariable logId: String
    ): ApiResponse<FallLogResponse> =
        ApiResponse.ok(fallLogService.confirmLog(userId, logId), "낙상 이벤트를 확인 처리했습니다.")

    @Operation(summary = "낙상 로그 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{userId}/{logId}")
    suspend fun deleteLog(
        @PathVariable userId: String,
        @PathVariable logId: String
    ): ApiResponse<Unit> {
        fallLogService.deleteLog(userId, logId)
        return ApiResponse.ok(message = "낙상 로그가 삭제되었습니다.")
    }
}
