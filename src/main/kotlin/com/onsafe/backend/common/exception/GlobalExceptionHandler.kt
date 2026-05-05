package com.onsafe.backend.common.exception

import com.onsafe.backend.common.response.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 비즈니스 로직 예외 처리 */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ApiResponse.fail(e.errorCode.message))
    }

    /** 입력값 유효성 검사 실패 */
    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationException(e: WebExchangeBindException): ResponseEntity<ApiResponse<Nothing>> {
        val message = e.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.fail(message))
    }

    /** JSON 역직렬화 실패 (필드명 불일치, 타입 오류 등) → 500 대신 400 반환 */
    @ExceptionHandler(ServerWebInputException::class)
    fun handleServerWebInputException(e: ServerWebInputException): ResponseEntity<ApiResponse<Nothing>> {
        val cause = e.cause?.message ?: e.message ?: "요청 형식이 올바르지 않습니다."
        val message = when {
            cause.contains("Missing required creator property") ->
                "필수 필드가 누락되었습니다. 요청 필드명이 snake_case인지 확인해 주세요. (예: user_id, device_id)"
            cause.contains("Cannot deserialize") ->
                "필드 타입이 올바르지 않습니다."
            else -> "요청 형식이 올바르지 않습니다."
        }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.fail(message))
    }

    /** 그 외 예상치 못한 예외 */
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception: ${e::class.simpleName} - ${e.message}", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("서버 내부 오류가 발생했습니다."))
    }
}
