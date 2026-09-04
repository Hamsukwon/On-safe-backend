package com.onsafe.backend.domain.camera.model.dto

data class RiskStatusResponse(
    val userId: String,
    val level: String,
    val score: Float,
    // 이 API는 현재 Android가 호출하지 않음(자체 임계값으로 재계산 중, RiskScoreCardBinder 참고).
    // 향후 실제 연동 시 클라이언트는 이 hex 값을 그대로 쓰지 말고 level만 받아 자체 테마 색상에
    // 매핑할 것 — 다크모드 등 클라이언트 리소스 기반 테마 시스템과 충돌할 수 있음.
    val colorCode: String
)
