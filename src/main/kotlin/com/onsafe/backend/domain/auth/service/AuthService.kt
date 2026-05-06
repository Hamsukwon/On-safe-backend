package com.onsafe.backend.domain.auth.service

import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
    private val emailService: EmailService,
    private val firestore: Firestore
) {

    private val resetCodes get() = firestore.collection("password_reset_codes")
    private val emailCodes get() = firestore.collection("email_verify_codes")

    suspend fun checkId(request: CheckIdRequest) {
        if (userRepository.existsByUserId(request.userId)) {
            throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
        }
    }

    suspend fun sendEmailCode(request: SendEmailCodeRequest) {
        val code = (100000..999999).random().toString()
        val expiresAt = LocalDateTime.now().plusMinutes(3)

        emailCodes.document(request.mail).set(
            mapOf("code" to code, "expires_at" to expiresAt.toTimestamp())
        ).await()

        emailService.sendEmailVerificationCode(request.mail, code)
    }

    suspend fun verifyEmailCode(request: VerifyEmailCodeRequest) {
        val doc = emailCodes.document(request.mail).get().await()
        if (!doc.exists()) throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)

        val expiresAt = doc.getTimestamp("expires_at")?.toLocalDateTime()
            ?: throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
        if (LocalDateTime.now().isAfter(expiresAt)) throw BusinessException(ErrorCode.EMAIL_CODE_EXPIRED)

        val storedCode = doc.getString("code") ?: throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)

        emailCodes.document(request.mail).delete().await()
    }

    suspend fun sendResetCode(request: SendResetCodeRequest) {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (user.mail != request.mail) throw BusinessException(ErrorCode.MAIL_NOT_MATCH)

        val code = (100000..999999).random().toString()
        val expiresAt = LocalDateTime.now().plusMinutes(3)

        resetCodes.document(request.userId).set(
            mapOf("code" to code, "expires_at" to expiresAt.toTimestamp())
        ).await()

        emailService.sendResetCode(request.mail, code)
    }

    suspend fun verifyResetCode(request: VerifyResetCodeRequest) {
        val doc = resetCodes.document(request.userId).get().await()
        if (!doc.exists()) throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        val expiresAt = doc.getTimestamp("expires_at")?.toLocalDateTime()
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        if (LocalDateTime.now().isAfter(expiresAt)) throw BusinessException(ErrorCode.RESET_CODE_EXPIRED)

        val storedCode = doc.getString("code") ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        resetCodes.document(request.userId).delete().await()
    }

    suspend fun register(request: RegisterRequest) {
        if (userRepository.existsByUserId(request.userId)) {
            throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
        }
        if (userRepository.existsByMail(request.mail)) {
            throw BusinessException(ErrorCode.MAIL_ALREADY_EXISTS)
        }
        userRepository.save(
            User(
                userId = request.userId,
                password = passwordEncoder.encode(request.password),
                name = request.name,
                phone = request.phone,
                wardName = "",
                mail = request.mail,
                address = request.address,
                addressDetail = request.addressDetail
            )
        )
    }

    suspend fun login(request: LoginRequest): LoginResponse {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw BusinessException(ErrorCode.INVALID_PASSWORD)
        }

        val tokens = issueTokens(user.userId, user.mail)
        return LoginResponse(
            userId = user.userId,
            deviceId = request.deviceId,
            name = user.name,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken
        )
    }

    suspend fun refresh(refreshToken: String): TokenResponse {
        if (!jwtProvider.validate(refreshToken)) {
            throw BusinessException(ErrorCode.EXPIRED_TOKEN)
        }
        return issueTokens(jwtProvider.getUserId(refreshToken), jwtProvider.getEmail(refreshToken))
    }

    suspend fun findId(request: FindIdRequest): FindIdResponse {
        val user = userRepository.findByMail(request.mail)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (user.name != request.name) throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return FindIdResponse(userId = maskUserId(user.userId))
    }

    suspend fun resetPassword(request: ResetPasswordRequest) {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        userRepository.save(user.copy(password = passwordEncoder.encode(request.newPassword)))
    }

    suspend fun updateFcmToken(request: FcmTokenRequest) {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        userRepository.save(user.copy(fcmToken = request.fcmToken))
    }

    private fun issueTokens(userId: String, mail: String) = TokenResponse(
        accessToken = jwtProvider.generateAccessToken(userId, mail),
        refreshToken = jwtProvider.generateRefreshToken(userId, mail)
    )

    private fun maskUserId(userId: String): String {
        if (userId.length <= 3) return userId
        return userId.take(3) + "*".repeat(userId.length - 3)
    }
}
