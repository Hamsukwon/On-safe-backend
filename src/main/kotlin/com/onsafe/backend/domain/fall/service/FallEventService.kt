package com.onsafe.backend.domain.fall.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.fall.model.dto.FallEventResponse
import com.onsafe.backend.domain.fall.repository.FallEventRepository
import org.springframework.stereotype.Service

@Service
class FallEventService(private val fallEventRepository: FallEventRepository) {

    suspend fun getFallEvents(userId: String): List<FallEventResponse> =
        fallEventRepository.findByUserIdOrderByDetectedAtDesc(userId).map { FallEventResponse.from(it) }

    suspend fun getFallEvent(eventId: String): FallEventResponse {
        val event = fallEventRepository.findById(eventId)
            ?: throw BusinessException(ErrorCode.FALL_EVENT_NOT_FOUND)
        return FallEventResponse.from(event)
    }

    suspend fun confirmFallEvent(eventId: String): FallEventResponse {
        val event = fallEventRepository.findById(eventId)
            ?: throw BusinessException(ErrorCode.FALL_EVENT_NOT_FOUND)
        fallEventRepository.save(event.copy(isConfirmed = true))
        return FallEventResponse.from(event.copy(isConfirmed = true))
    }
}
