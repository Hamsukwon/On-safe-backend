package com.onsafe.backend.domain.notification.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.model.dto.NotificationResponse
import com.onsafe.backend.domain.user.repository.UserRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class NotificationService(private val userRepository: UserRepository) {

    suspend fun sendNotification(request: NotificationRequest): NotificationResponse {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        val fcmToken = user.fcmToken
            ?: return NotificationResponse(status = "ok", message = "FCM 토큰이 없습니다.", fcmMessageId = "")

        /*
         * FCM 실제 발송 (Firebase Admin SDK 활성화 후 아래 코드로 교체):
         *
         * val messageBuilder = Message.builder()
         *     .setToken(fcmToken)
         *     .setNotification(Notification.builder().setTitle(request.title).setBody(request.body).build())
         * request.data?.forEach { (k, v) -> messageBuilder.putData(k, v) }
         * val messageId = FirebaseMessaging.getInstance().sendAsync(messageBuilder.build()).await()
         */
        val messageId = "projects/onsafe/messages/${UUID.randomUUID()}"

        return NotificationResponse(status = "ok", message = "알림 전송 완료", fcmMessageId = messageId)
    }
}
