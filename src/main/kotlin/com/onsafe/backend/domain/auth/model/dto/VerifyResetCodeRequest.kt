package com.onsafe.backend.domain.auth.model.dto

import jakarta.validation.constraints.NotBlank

data class VerifyResetCodeRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    val userId: String,

    @field:NotBlank(message = "인증 코드를 입력해주세요.")
    val code: String
)
