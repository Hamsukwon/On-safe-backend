package com.onsafe.backend.domain.auth.model.dto

import jakarta.validation.constraints.NotBlank

data class FcmTokenRequest(
    @field:NotBlank(message = "사용자 ID를 입력해주세요.")
    val userId: String,

    @field:NotBlank(message = "FCM 토큰을 입력해주세요.")
    val fcmToken: String
)
