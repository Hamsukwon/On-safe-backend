package com.onsafe.backend.domain.camera.model.dto

import java.time.LocalDateTime

data class RiskScoreResponse(
    val userId: String,
    val score: Float,
    val level: String,
    val updatedAt: LocalDateTime,
    // 추론 성공 여부와 무관한 "원시 프레임 도착" 시각 — updatedAt과 분리해 연결 끊김/처리
    // 지연을 구분하는 데 쓰인다(Android MainViewModel 참고).
    val deviceSeenAt: LocalDateTime?
)
