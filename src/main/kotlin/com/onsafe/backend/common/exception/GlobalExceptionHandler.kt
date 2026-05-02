package com.onsafe.backend.common.exception

import com.onsafe.backend.common.response.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException

@RestControllerAdvice
class GlobalExceptionHandler {

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

    /** 그 외 예상치 못한 예외 */
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("서버 내부 오류가 발생했습니다."))
    }
}
