package com.onsafe.backend.domain.internal.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.internal.model.dto.ReportFallRequest
import com.onsafe.backend.domain.internal.model.dto.SaveDetectionLogRequest
import com.onsafe.backend.domain.internal.model.dto.UpdateRealtimeRequest
import com.onsafe.backend.domain.internal.service.InternalService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Python AI 서버 전용 내부 API — JWT 인증 없이 로컬 네트워크에서만 호출
@RestController
@RequestMapping("/internal")
class InternalController(private val internalService: InternalService) {

    @Operation(summary = "실시간 위험 점수 업데이트 (AI 서버 전용)", security = [])
    @PostMapping("/realtime")
    suspend fun updateRealtime(@RequestBody req: UpdateRealtimeRequest): ApiResponse<Unit> {
        internalService.updateRealtime(req)
        return ApiResponse.ok("실시간 데이터 업데이트 완료")
    }

    @Operation(summary = "낙상 감지 보고 + FCM 발송 (AI 서버 전용)", security = [])
    @PostMapping("/fall")
    suspend fun reportFall(@RequestBody req: ReportFallRequest): ApiResponse<Unit> {
        internalService.reportFall(req)
        return ApiResponse.ok("낙상 이벤트 저장 및 알림 발송 완료")
    }

    @Operation(summary = "감지 로그 저장 (AI 서버 전용)", security = [])
    @PostMapping("/detection-log")
    suspend fun saveDetectionLog(@RequestBody req: SaveDetectionLogRequest): ApiResponse<Unit> {
        internalService.saveDetectionLog(req)
        return ApiResponse.ok("감지 로그 저장 완료")
    }
}
