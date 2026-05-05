package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.NotBlank

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FcmTokenRequest(
    @field:NotBlank(message = "사용자 ID를 입력해주세요.")
    val userId: String,

    @field:NotBlank(message = "FCM 토큰을 입력해주세요.")
    val fcmToken: String
)
