package com.onsafe.backend.domain.fall.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.fall.model.dto.FallEventResponse
import com.onsafe.backend.domain.fall.service.FallEventService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@Tag(name = "FallEvent", description = "낙상 이벤트 API")
@RestController
@RequestMapping("/api/fall-events")
class FallEventController(private val fallEventService: FallEventService) {

    @Operation(summary = "사용자의 낙상 이벤트 목록 조회")
    @GetMapping("/{userId}")
    suspend fun getFallEvents(@PathVariable userId: String): ApiResponse<List<FallEventResponse>> =
        ApiResponse.ok(fallEventService.getFallEvents(userId))

    @Operation(summary = "낙상 이벤트 단건 조회")
    @GetMapping("/detail/{eventId}")
    suspend fun getFallEvent(@PathVariable eventId: String): ApiResponse<FallEventResponse> =
        ApiResponse.ok(fallEventService.getFallEvent(eventId))

    @Operation(summary = "낙상 이벤트 확인 처리")
    @PatchMapping("/{eventId}/confirm")
    suspend fun confirmFallEvent(@PathVariable eventId: String): ApiResponse<FallEventResponse> =
        ApiResponse.ok(fallEventService.confirmFallEvent(eventId), "낙상 이벤트를 확인 처리했습니다.")
}
