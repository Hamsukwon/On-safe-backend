
package com.onsafe.backend.domain.camera.model.dto

import jakarta.validation.constraints.NotBlank

data class CameraUrlRequest(
    @field:NotBlank(message = "카메라 URL을 입력해주세요.")
    val cameraUrl: String
)
