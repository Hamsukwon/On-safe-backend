package com.onsafe.backend.domain.auth.model.dto

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    val userId: String,

    @field:NotBlank(message = "비밀번호를 입력해주세요.")
    val password: String,

    @field:NotBlank(message = "기기 ID를 입력해주세요.")
    val deviceId: String
)
