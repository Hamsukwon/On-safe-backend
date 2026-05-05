package com.onsafe.backend.domain.auth.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.auth.service.AuthService
import com.onsafe.backend.domain.user.model.dto.UserRegisterRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @Operation(summary = "회원가입")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun register(@Valid @RequestBody request: UserRegisterRequest): ApiResponse<Unit> {
        authService.register(request)
        return ApiResponse.ok(message = "회원가입이 완료되었습니다.")
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    suspend fun login(@Valid @RequestBody request: LoginRequest): ApiResponse<LoginResponse> {
        val response = authService.login(request)
        return ApiResponse.ok(response)
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    suspend fun logout(): ApiResponse<Unit> {
        return ApiResponse.ok(message = "로그아웃 완료")
    }

    @Operation(summary = "아이디 찾기")
    @PostMapping("/find-id")
    suspend fun findId(@Valid @RequestBody request: FindIdRequest): ApiResponse<FindIdResponse> {
        val response = authService.findId(request)
        return ApiResponse.ok(response)
    }

    @Operation(summary = "비밀번호 재설정 코드 발송")
    @PostMapping("/send-reset-code")
    suspend fun sendResetCode(@Valid @RequestBody request: SendResetCodeRequest): ApiResponse<Unit> {
        authService.sendResetCode(request)
        return ApiResponse.ok(message = "재설정 코드를 발송했습니다.")
    }

    @Operation(summary = "비밀번호 재설정 코드 확인")
    @PostMapping("/verify-reset-code")
    suspend fun verifyResetCode(@Valid @RequestBody request: VerifyResetCodeRequest): ApiResponse<Unit> {
        authService.verifyResetCode(request)
        return ApiResponse.ok(message = "코드 확인이 완료되었습니다.")
    }

    @Operation(summary = "비밀번호 재설정")
    @PostMapping("/reset-password")
    suspend fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): ApiResponse<Unit> {
        authService.resetPassword(request)
        return ApiResponse.ok(message = "비밀번호가 변경되었습니다")
    }

    @Operation(summary = "FCM 토큰 등록/갱신")
    @PostMapping("/fcm-token")
    suspend fun updateFcmToken(@Valid @RequestBody request: FcmTokenRequest): ApiResponse<Unit> {
        authService.updateFcmToken(request)
        return ApiResponse.ok(message = "FCM 토큰 등록 완료")
    }

    @Operation(summary = "토큰 재발급")
    @PostMapping("/refresh")
    suspend fun refresh(@RequestHeader("Refresh-Token") refreshToken: String): ApiResponse<TokenResponse> {
        val tokens = authService.refresh(refreshToken)
        return ApiResponse.ok(tokens)
    }
}
