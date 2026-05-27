package com.onsafe.backend.domain.auth

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.auth.service.EmailService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.model.SendEmailRequest
import software.amazon.awssdk.services.ses.model.SendEmailResponse
import software.amazon.awssdk.services.ses.model.SesException
import java.util.concurrent.CompletableFuture

class EmailServiceTest {

    private val sesClient: SesAsyncClient = mockk()
    private lateinit var emailService: EmailService

    @BeforeEach
    fun setUp() {
        emailService = EmailService(sesClient, "no-reply@test.com")
    }

    @Test
    fun `이메일 발송 성공 - 정상 처리된다`() = runTest {
        every { sesClient.sendEmail(any<SendEmailRequest>()) } returns
            CompletableFuture.completedFuture(SendEmailResponse.builder().messageId("msg-id").build())

        emailService.sendEmailVerificationCode("user@example.com", "123456")
    }

    @Test
    fun `SdkClientException 발생 시 MAIL_SEND_FAILED 예외로 변환된다`() = runTest {
        val future = CompletableFuture<SendEmailResponse>()
        future.completeExceptionally(SdkClientException.create("Connection refused"))
        every { sesClient.sendEmail(any<SendEmailRequest>()) } returns future

        val thrown = runCatching {
            emailService.sendEmailVerificationCode("user@example.com", "123456")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.MAIL_SEND_FAILED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `SesException 발생 시 MAIL_SEND_FAILED 예외로 변환된다`() = runTest {
        val future = CompletableFuture<SendEmailResponse>()
        future.completeExceptionally(
            SesException.builder()
                .message("MessageRejected")
                .statusCode(400)
                .awsErrorDetails(
                    AwsErrorDetails.builder()
                        .errorCode("MessageRejected")
                        .errorMessage("Email address is not verified")
                        .build()
                )
                .build()
        )
        every { sesClient.sendEmail(any<SendEmailRequest>()) } returns future

        val thrown = runCatching {
            emailService.sendEmailVerificationCode("user@example.com", "123456")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.MAIL_SEND_FAILED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `sendResetCode - SdkClientException 발생 시 MAIL_SEND_FAILED 예외로 변환된다`() = runTest {
        val future = CompletableFuture<SendEmailResponse>()
        future.completeExceptionally(SdkClientException.create("Timeout"))
        every { sesClient.sendEmail(any<SendEmailRequest>()) } returns future

        val thrown = runCatching {
            emailService.sendResetCode("user@example.com", "654321")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.MAIL_SEND_FAILED, (thrown as BusinessException).errorCode)
    }
}
