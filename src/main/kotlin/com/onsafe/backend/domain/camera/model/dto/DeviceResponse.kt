package com.onsafe.backend.domain.camera.model.dto

data class DeviceResponse(
    val deviceId: String,
    val deviceName: String,
    val status: String,
    val lastSeen: String?
)
