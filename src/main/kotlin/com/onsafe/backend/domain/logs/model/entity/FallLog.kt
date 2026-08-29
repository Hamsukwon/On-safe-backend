package com.onsafe.backend.domain.logs.model.entity

import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import java.time.LocalDateTime

data class FallLog(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean = false,
    val videoUrl: String? = null,
    val lastReminderAt: LocalDateTime? = null,  // 미확인 위험 이벤트 에스컬레이션 리마인더 마지막 발송 시각
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    // 영상은 위험(75 초과) 등급 로그에만 제공한다는 정책(8절) — 업로드 허용 여부와 상태 계산 양쪽에서 재사용.
    val isDangerLevel: Boolean get() = score > RiskLevel.DANGER_THRESHOLD || fall
}
