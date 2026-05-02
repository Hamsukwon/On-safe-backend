package com.onsafe.backend.domain.user.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    suspend fun getUser(userId: String): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return UserResponse.from(user)
    }

    suspend fun updateUser(userId: String, request: UserUpdateRequest): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        val updated = user.copy(
            name = request.name ?: user.name,
            password = if (request.password != null) passwordEncoder.encode(request.password) else user.password,
            mail = request.mail ?: user.mail,
            phone = request.phone ?: user.phone
        )
        return UserResponse.from(userRepository.save(updated))
    }

    suspend fun deleteUser(userId: String) {
        if (!userRepository.existsByUserId(userId)) {
            throw BusinessException(ErrorCode.USER_NOT_FOUND)
        }
        userRepository.deleteByUserId(userId)
    }
}