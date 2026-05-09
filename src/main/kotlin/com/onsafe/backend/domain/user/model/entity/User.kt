package com.onsafe.backend.domain.user.model.entity

import java.time.LocalDateTime

data class User(
    val userId: String,
    val password: String,
    val name: String,
    val phone: String,
    val mail: String,
    val address: String? = null,
    val addressDetail: String? = null,
    val fcmToken: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
