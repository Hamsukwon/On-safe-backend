package com.onsafe.backend.domain.logs.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.logs.model.dto.FallLogResponse
import com.onsafe.backend.domain.logs.service.FallLogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

@Tag(name = "FallLogs", description = "낙상 로그 API")
@RestController
@RequestMapping("/api/fall-logs")
class FallLogController(private val fallLogService: FallLogService) {

    @Operation(
        summary = "낙상 로그 목록 조회",
        description = "level 파라미터로 필터링 가능. 값: 위험 | 주의 (생략 시 전체)",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}")
    suspend fun getLogs(
        @PathVariable userId: String,
        @RequestParam(required = false) level: String?
    ): ApiResponse<Map<String, List<FallLogResponse>>> =
        ApiResponse.ok(mapOf("logs" to fallLogService.getLogs(userId, level)))

    @Operation(summary = "낙상 로그 탭별 건수 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}/counts")
    suspend fun getLogCounts(@PathVariable userId: String): ApiResponse<Map<String, Int>> =
        ApiResponse.ok(fallLogService.getLogCounts(userId))

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

    @Operation(
        summary = "낙상 썸네일 signed URL 조회 (#6)",
        description = "1시간 유효한 signed URL을 반환합니다. 썸네일이 없으면 404를 반환합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}/{logId}/thumbnail")
    suspend fun getThumbnail(
        @PathVariable userId: String,
        @PathVariable logId: String
    ): ApiResponse<Map<String, String>> {
        val signedUrl = fallLogService.getSignedUrl(userId, logId)
        return ApiResponse.ok(mapOf("signed_url" to signedUrl))
    }

    @Operation(
        summary = "낙상 썸네일 다운로드 (#7)",
        description = "signed URL로 302 redirect합니다. 썸네일이 없으면 404를 반환합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}/{logId}/download")
    suspend fun downloadThumbnail(
        @PathVariable userId: String,
        @PathVariable logId: String
    ): ResponseEntity<Unit> {
        val signedUrl = fallLogService.getSignedUrl(userId, logId)
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(signedUrl))
            .build()
    }
}
