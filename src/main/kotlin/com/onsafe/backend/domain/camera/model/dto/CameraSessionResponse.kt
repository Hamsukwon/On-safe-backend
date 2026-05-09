package com.onsafe.backend.domain.camera.model.dto

import com.onsafe.backend.domain.camera.model.entity.CameraSessionStatus

data class CameraSessionResponse(
    val userId: String,
    val status: CameraSessionStatus,
    val startedAt: String? = null,
    val elapsedSeconds: Long? = null
)
