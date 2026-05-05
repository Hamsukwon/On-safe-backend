package com.onsafe.backend.domain.auth.model.dto

import jakarta.validation.constraints.NotBlank

data class SendResetCodeRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    val userId: String,

    @field:NotBlank(message = "이메일을 입력해주세요.")
    val mail: String
)
