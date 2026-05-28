package com.onsafe.backend.domain.notification.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.util.await
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.model.dto.NotificationResponse
import com.onsafe.backend.domain.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class NotificationService(private val userRepository: UserRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun sendNotification(request: NotificationRequest): NotificationResponse {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        val fcmToken = user.fcmToken
            ?: return NotificationResponse(status = "ok", message = "FCM 토큰이 없습니다.", fcmMessageId = "")

        val messageBuilder = Message.builder()
            .setToken(fcmToken)
            .setNotification(
                Notification.builder()
                    .setTitle(request.title)
                    .setBody(request.body)
                    .build()
            )
        request.data?.forEach { (k, v) -> messageBuilder.putData(k, v) }

        return try {
            val messageId = FirebaseMessaging.getInstance().sendAsync(messageBuilder.build()).await()
            NotificationResponse(status = "ok", message = "알림 전송 완료", fcmMessageId = messageId)
        } catch (e: Exception) {
            log.warn("FCM 전송 실패 (userId: ${request.userId}): ${e.message}")
            throw BusinessException(ErrorCode.FCM_SEND_FAILED)
        }
    }
}
