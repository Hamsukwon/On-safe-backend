package com.onsafe.backend.common.email

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

@Service
class EmailService(private val mailSender: JavaMailSender) {

    suspend fun sendResetCode(to: String, code: String) = withContext(Dispatchers.IO) {
        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, "UTF-8")
            helper.setTo(to)
            helper.setSubject("[OnSafe] 비밀번호 재설정 코드")
            helper.setText(
                """
                <div style="font-family:sans-serif;max-width:480px;margin:auto">
                  <h3>비밀번호 재설정 코드</h3>
                  <p>아래 6자리 코드를 앱에 입력해주세요. 코드는 <b>3분</b>간 유효합니다.</p>
                  <h2 style="letter-spacing:6px;font-size:32px">$code</h2>
                  <p style="color:#888;font-size:12px">본인이 요청하지 않은 경우 이 이메일을 무시해주세요.</p>
                </div>
                """.trimIndent(),
                true
            )
            mailSender.send(message)
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        }
    }
}
