package com.onsafe.backend.domain.auth

import com.onsafe.backend.domain.auth.model.dto.RegisterRequest
import com.onsafe.backend.domain.auth.model.dto.ResetPasswordRequest
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 비밀번호 정규식(RegisterRequest/ResetPasswordRequest/UserUpdateRequest 동일 패턴)의
 * DOTALL 누락 회귀 테스트. "." 가 개행과 매치되지 않으면 영문+숫자를 모두 포함한
 * 정상 비밀번호도 개행이 섞여 있으면 거부됐었다.
 */
class PasswordPatternTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun hasPasswordViolation(password: String): Boolean =
        validator.validate(
            RegisterRequest(
                userId = "testUser",
                password = password,
                name = "홍길동",
                mail = "test@test.com",
                phone = "010-1234-5678"
            )
        ).any { it.propertyPath.toString() == "password" }

    @Test
    fun `개행이 포함돼도 영문+숫자를 만족하면 통과한다 (DOTALL 회귀)`() {
        assertFalse(hasPasswordViolation("abc123\nXY"))
    }

    @Test
    fun `숫자가 없으면 여전히 거부된다`() {
        assertTrue(hasPasswordViolation("abcdefgh"))
    }

    @Test
    fun `영문이 없으면 여전히 거부된다`() {
        assertTrue(hasPasswordViolation("12345678"))
    }

    @Test
    fun `ResetPasswordRequest도 개행 포함 비밀번호를 통과시킨다`() {
        val violations = validator.validate(
            ResetPasswordRequest(userId = "testUser", newPassword = "abc123\nXY")
        )
        assertFalse(violations.any { it.propertyPath.toString() == "newPassword" })
    }

    @Test
    fun `UserUpdateRequest도 개행 포함 비밀번호를 통과시킨다`() {
        val violations = validator.validate(UserUpdateRequest(password = "abc123\nXY"))
        assertFalse(violations.any { it.propertyPath.toString() == "password" })
    }
}
