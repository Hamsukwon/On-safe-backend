package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.ErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtProvider(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.access-token-expiry}") private val accessTokenExpiry: Long,
    @Value("\${jwt.refresh-token-expiry}") private val refreshTokenExpiry: Long
) {
    private val signingKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray())
    }

    fun generateAccessToken(userId: String, email: String): String =
        buildToken(userId, email, accessTokenExpiry)

    fun generateRefreshToken(userId: String, email: String): String =
        buildToken(userId, email, refreshTokenExpiry)

    fun getUserId(token: String): String =
        parseClaims(token)["userId"].toString()

    fun getEmail(token: String): String =
        parseClaims(token).subject

    /**
     * 토큰 유효성 검사 — 만료와 서명/형식 오류를 구분해 ErrorCode로 반환.
     * null 이면 유효한 토큰.
     */
    fun getValidationError(token: String): ErrorCode? = try {
        parseClaims(token)
        null
    } catch (e: ExpiredJwtException) {
        ErrorCode.EXPIRED_TOKEN
    } catch (e: Exception) {
        ErrorCode.INVALID_TOKEN
    }

    fun validate(token: String): Boolean = getValidationError(token) == null

    fun getRemainingExpiry(token: String): Duration = runCatching {
        val remaining = parseClaims(token).expiration.time - System.currentTimeMillis()
        if (remaining > 0) Duration.ofMillis(remaining) else Duration.ZERO
    }.getOrDefault(Duration.ZERO)

    private fun buildToken(userId: String, email: String, expiry: Long): String {
        val now = Date()
        return Jwts.builder()
            .subject(email)
            .claim("userId", userId)
            .issuedAt(now)
            .expiration(Date(now.time + expiry))
            .signWith(signingKey)
            .compact()
    }

    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(signingKey).build()
            .parseSignedClaims(token).payload
}
