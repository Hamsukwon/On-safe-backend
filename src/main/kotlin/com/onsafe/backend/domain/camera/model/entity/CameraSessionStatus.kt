package com.onsafe.backend.domain.camera.model.entity

enum class CameraSessionStatus {
    STANDBY,     // 카메라 비활성 (촬영 대기 중)
    CONNECTING,  // 카메라 활성화 시작 (첫 프레임 수신 대기 중)
    LIVE         // 프레임 수신 중 (실시간 스트리밍 활성)
}
