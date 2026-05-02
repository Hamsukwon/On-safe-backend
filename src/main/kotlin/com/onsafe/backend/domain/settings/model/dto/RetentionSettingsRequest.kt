package com.onsafe.backend.domain.settings.model.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class RetentionSettingsRequest(
    @field:Min(1, message = "최소 1일 이상이어야 합니다.")
    @field:Max(365, message = "최대 365일까지 설정 가능합니다.")
    val retentionDays: Int
)
