package com.onsafe.backend.domain.camera.model.entity

import java.time.LocalDateTime

data class RealtimeData(
    val userId: String,
    val score: Float = 0f,
    val level: String = "정상",
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    // AI 추론 성공 여부와 무관하게 "원시 프레임이 도착했다"는 사실만 기록하는 하트비트.
    // updatedAt(추론 성공 시각)과 분리해둬야 "완전히 끊김"과 "느려짐/추론만 실패"를 구분할 수 있다.
    val deviceSeenAt: LocalDateTime? = null
)
