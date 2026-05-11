package com.onsafe.backend.domain.auth.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Duration

private const val EMAIL_CODE_TTL = 180L    // 3분
private const val RESET_CODE_TTL = 180L    // 3분
private const val RESET_VERIFIED_TTL = 600L // 10분 — verifyResetCode 성공 후 resetPassword 가능 시간

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
    private val emailService: EmailService,
    private val redis: ReactiveStringRedisTemplate
) {

    suspend fun logout(token: String) {
        val remaining = jwtProvider.getRemainingExpiry(token)
        if (remaining > java.time.Duration.ZERO) {
            redis.opsForValue().set("bl:$token", "1", remaining).awaitSingle()
        }
    }

    suspend fun checkId(request: CheckIdRequest) {
        if (userRepository.existsByUserId(request.userId)) {
            throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
        }
    }

    suspend fun sendEmailCode(request: SendEmailCodeRequest) {
        val code = generateVerificationCode()
        redis.opsForValue()
            .set("email_verify:${request.mail}", code, Duration.ofSeconds(EMAIL_CODE_TTL))
            .awaitSingle()
        emailService.sendEmailVerificationCode(request.mail, code)
    }

    suspend fun verifyEmailCode(request: VerifyEmailCodeRequest) {
        val key = "email_verify:${request.mail}"
        val storedCode = redis.opsForValue().get(key).awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
        redis.delete(key).awaitSingle()
    }

    suspend fun sendResetCode(request: SendResetCodeRequest) {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (user.mail != request.mail) throw BusinessException(ErrorCode.MAIL_NOT_MATCH)

        val code = generateVerificationCode()
        redis.opsForValue()
            .set("reset_code:${request.userId}", code, Duration.ofSeconds(RESET_CODE_TTL))
            .awaitSingle()
        emailService.sendResetCode(request.mail, code)
    }

    suspend fun verifyResetCode(request: VerifyResetCodeRequest) {
        val key = "reset_code:${request.userId}"
        val storedCode = redis.opsForValue().get(key).awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        redis.delete(key).awaitSingle()
        redis.opsForValue()
            .set("reset_verified:${request.userId}", "1", Duration.ofSeconds(RESET_VERIFIED_TTL))
            .awaitSingle()
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
        val isBlacklisted = redis.opsForValue().get("bl:$refreshToken").awaitFirstOrNull()
        if (isBlacklisted != null) throw BusinessException(ErrorCode.INVALID_TOKEN)

        val tokens = issueTokens(jwtProvider.getUserId(refreshToken), jwtProvider.getEmail(refreshToken))

        val remaining = jwtProvider.getRemainingExpiry(refreshToken)
        if (remaining > java.time.Duration.ZERO) {
            redis.opsForValue().set("bl:$refreshToken", "1", remaining).awaitSingle()
        }
        return tokens
    }

    suspend fun findId(request: FindIdRequest): FindIdResponse {
        val user = userRepository.findByMail(request.mail)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (user.name != request.name) throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return FindIdResponse(userId = maskUserId(user.userId))
    }

    suspend fun resetPassword(request: ResetPasswordRequest) {
        val verifiedKey = "reset_verified:${request.userId}"
        redis.opsForValue().get(verifiedKey).awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        userRepository.save(user.copy(password = passwordEncoder.encode(request.newPassword)))
        redis.delete(verifiedKey).awaitSingle()
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

    private fun generateVerificationCode() = (100000..999999).random().toString()

    private fun maskUserId(userId: String): String {
        if (userId.length <= 3) return userId
        return userId.take(3) + "*".repeat(userId.length - 3)
    }
}
