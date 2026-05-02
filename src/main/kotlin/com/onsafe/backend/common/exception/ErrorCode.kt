package com.onsafe.backend.common.exception

import org.springframework.http.HttpStatus

/**
 * 애플리케이션 전역 에러 코드 정의
 * 새로운 도메인 에러는 해당 섹션에 추가
 */
enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    // ── 공통 ──────────────────────────────────────────────
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),

    // ── 인증/회원 ──────────────────────────────────────────
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    MAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),

    // ── 피보호자(노인) ──────────────────────────────────────
    ELDER_NOT_FOUND(HttpStatus.NOT_FOUND, "피보호자 정보를 찾을 수 없습니다."),

    // ── 낙상 이벤트 ───────────────────────────────────────
    FALL_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "낙상 이벤트를 찾을 수 없습니다."),

    // ── 기기 ──────────────────────────────────────────────
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "기기를 찾을 수 없습니다."),
    DEVICE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 등록된 기기입니다."),

    // ── 사고 이력 ─────────────────────────────────────────
    LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "사고 이력을 찾을 수 없습니다."),

    // ── 설정 ──────────────────────────────────────────────
    SETTINGS_NOT_FOUND(HttpStatus.NOT_FOUND, "설정 정보를 찾을 수 없습니다."),

    // ── 카메라 ────────────────────────────────────────────
    CAMERA_NOT_FOUND(HttpStatus.NOT_FOUND, "카메라 정보를 찾을 수 없습니다."),
    REALTIME_DATA_NOT_FOUND(HttpStatus.NOT_FOUND, "실시간 데이터가 없습니다."),

    // ── 아이디 찾기 ───────────────────────────────────────
    USER_ID_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.")
}
