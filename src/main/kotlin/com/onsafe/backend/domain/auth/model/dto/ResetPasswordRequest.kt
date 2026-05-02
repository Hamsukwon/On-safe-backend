package com.onsafe.backend.domain.auth.model.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ResetPasswordRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    val userId: String,

    @field:Size(min = 4, message = "비밀번호는 4자 이상이어야 합니다.")
    val newPassword: String
)
