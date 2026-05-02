package com.onsafe.backend.domain.logs.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.logs.model.dto.DetectionLogResponse
import com.onsafe.backend.domain.logs.repository.DetectionLogRepository
import org.springframework.stereotype.Service

@Service
class LogsService(private val detectionLogRepository: DetectionLogRepository) {

    suspend fun getLogs(userId: String): List<DetectionLogResponse> =
        detectionLogRepository.findRecentByUserId(userId).map { DetectionLogResponse.from(it) }

    suspend fun getLog(userId: String, logId: String): DetectionLogResponse {
        val log = detectionLogRepository.findByLogIdAndUserId(logId, userId)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return DetectionLogResponse.from(log)
    }

    suspend fun deleteLog(userId: String, logId: String) {
        val deleted = detectionLogRepository.deleteByLogIdAndUserId(logId, userId)
        if (deleted == 0L) throw BusinessException(ErrorCode.LOG_NOT_FOUND)
    }
}
