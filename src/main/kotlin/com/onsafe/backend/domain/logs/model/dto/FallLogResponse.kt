package com.onsafe.backend.domain.logs.model.dto

import com.onsafe.backend.domain.logs.model.entity.FallLog
import java.time.LocalDateTime

data class FallLogResponse(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean,
    val hasVideo: Boolean,
    // hasVideo(=videoUrl != null)만으로는 "이 로그엔 애초에 영상이 없음(주의 등급)"과
    // "위험 등급이라 영상이 생성될 예정이나 post-이벤트 녹화·업로드가 아직 안 끝남"을
    // 클라이언트가 구분할 수 없었다 — 낙상 직후 2분 남짓은 정상적으로 영상이 없는 상태인데
    // 이걸 "영상 없음(none)"과 동일하게 보여주면 사용자가 버그로 오인한다. videoStatus로 3가지
    // 상태를 명시한다: none(주의 등급 등 애초에 미제공) / processing(위험 등급, 아직 준비 중) /
    // ready(hasVideo=true와 동일 조건, 재생 가능).
    val videoStatus: String,
    val timestamp: LocalDateTime
) {
    companion object {
        fun from(log: FallLog) = FallLogResponse(
            logId = log.logId,
            deviceId = log.deviceId,
            userId = log.userId,
            score = log.score,
            fall = log.fall,
            isConfirmed = log.isConfirmed,
            hasVideo = log.videoUrl != null,
            videoStatus = when {
                log.videoUrl != null -> "ready"
                log.isDangerLevel -> "processing"
                else -> "none"
            },
            timestamp = log.timestamp
        )
    }
}
