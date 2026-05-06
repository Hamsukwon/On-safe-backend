package com.onsafe.backend.domain.auth.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

@Service
class EmailService(private val mailSender: JavaMailSender) {

    suspend fun sendResetCode(to: String, code: String) = withContext(Dispatchers.IO) {
        try {
            val message = SimpleMailMessage()
            message.setTo(to)
            message.subject = "[OnSafe] 비밀번호 재설정 인증코드"
            message.text = """
                안녕하세요, OnSafe입니다.

                비밀번호 재설정 인증코드: $code

                인증코드는 3분간 유효합니다.
                본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
            """.trimIndent()
            mailSender.send(message)
        } catch (e: MailException) {
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        }
    }

    suspend fun sendEmailVerificationCode(to: String, code: String) = withContext(Dispatchers.IO) {
        try {
            val message = SimpleMailMessage()
            message.setTo(to)
            message.subject = "[OnSafe] 이메일 인증코드"
            message.text = """
                안녕하세요, OnSafe입니다.

                이메일 인증코드: $code

                인증코드는 3분간 유효합니다.
                본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
            """.trimIndent()
            mailSender.send(message)
        } catch (e: MailException) {
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        }
    }
}
