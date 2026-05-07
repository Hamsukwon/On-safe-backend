package com.onsafe.backend.domain.logs.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.logs.model.dto.FallLogResponse
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import org.springframework.stereotype.Service

@Service
class FallLogService(private val fallLogRepository: FallLogRepository) {

    suspend fun getLogs(userId: String): List<FallLogResponse> =
        fallLogRepository.findRecentByUserId(userId).map { FallLogResponse.from(it) }

    suspend fun getLog(userId: String, logId: String): FallLogResponse {
        val log = fallLogRepository.findByLogIdAndUserId(logId, userId)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return FallLogResponse.from(log)
    }

    suspend fun confirmLog(userId: String, logId: String): FallLogResponse {
        val log = fallLogRepository.confirmByLogIdAndUserId(logId, userId)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return FallLogResponse.from(log)
    }

    suspend fun deleteLog(userId: String, logId: String) {
        val deleted = fallLogRepository.deleteByLogIdAndUserId(logId, userId)
        if (deleted == 0L) throw BusinessException(ErrorCode.LOG_NOT_FOUND)
    }
}
