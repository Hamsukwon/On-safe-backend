package com.onsafe.backend.domain.user.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "User", description = "사용자 관련 API")
@RestController
@RequestMapping("/api/user")
class UserController(private val userService: UserService) {

    @Operation(summary = "사용자 정보 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}")
    suspend fun getUser(@PathVariable userId: String): ApiResponse<UserResponse> {
        return ApiResponse.ok(userService.getUser(userId))
    }

    @Operation(summary = "개인정보 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/{userId}")
    suspend fun updateUser(
        @PathVariable userId: String,
        @Valid @RequestBody request: UserUpdateRequest
    ): ApiResponse<UserResponse> {
        return ApiResponse.ok(userService.updateUser(userId, request), "개인정보가 수정되었습니다.")
    }

    @Operation(summary = "회원 탈퇴", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{userId}")
    suspend fun deleteUser(@PathVariable userId: String): ApiResponse<Unit> {
        userService.deleteUser(userId)
        return ApiResponse.ok(message = "회원 탈퇴가 완료되었습니다.")
    }
}
