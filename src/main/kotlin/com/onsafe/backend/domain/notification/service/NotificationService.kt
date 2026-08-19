package com.onsafe.backend.domain.notification.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification as FcmNotification
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.util.await
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationLogResponse
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.model.dto.NotificationResponse
import com.onsafe.backend.domain.notification.model.entity.Notification
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class NotificationService(
    private val userRepository: UserRepository,
    private val notificationRepository: NotificationRepository,
    private val guardianLinkRepository: GuardianLinkRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun sendNotification(request: NotificationRequest): NotificationResponse {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return sendNotification(user, request)
    }

    // 이미 조회해둔 User가 있는 호출부(notifyElderAndGuardians)가 같은 유저를 다시 조회하지
    // 않도록 분리한 내부 버전. 공개 API인 sendNotification(request)는 여전히 직접 조회한다.
    private suspend fun sendNotification(user: User, request: NotificationRequest): NotificationResponse {
        // FCM 발송 성공/실패/토큰 없음과 무관하게 알림 목록에는 항상 남긴다 — 목록 API가
        // push 전송 결과에 의존하지 않고 "무슨 알림이 발생했는지"를 그대로 반영해야 하기 때문.
        notificationRepository.save(
            Notification(
                userId = request.userId,
                title = request.title,
                body = request.body,
                logId = request.logId,
                score = request.score,
                fall = request.fall,
            )
        )

        val fcmToken = user.fcmToken
            ?: return NotificationResponse(status = "ok", message = "FCM 토큰이 없습니다.", fcmMessageId = "")

        val messageBuilder = Message.builder()
            .setToken(fcmToken)
            .setNotification(
                FcmNotification.builder()
                    .setTitle(request.title)
                    .setBody(request.body)
                    .build()
            )
        request.data?.forEach { (k, v) -> messageBuilder.putData(k, v) }

        return try {
            val messageId = FirebaseMessaging.getInstance().sendAsync(messageBuilder.build()).await()
            NotificationResponse(status = "ok", message = "알림 전송 완료", fcmMessageId = messageId)
        } catch (e: FirebaseMessagingException) {
            if (e.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                log.warn("FCM 토큰 만료로 삭제 (userId: ${request.userId})")
                userRepository.clearFcmToken(request.userId)
            } else {
                log.warn("FCM 전송 실패 (userId: ${request.userId}): ${e.message}")
            }
            throw BusinessException(ErrorCode.FCM_SEND_FAILED)
        } catch (e: Exception) {
            log.warn("FCM 전송 실패 (userId: ${request.userId}): ${e.message}")
            throw BusinessException(ErrorCode.FCM_SEND_FAILED)
        }
    }

    /**
     * 피보호자 본인 + 연결된 보호자 전원에게 알림을 보낸다. 발송 대상별로 개별 실패를 격리해
     * 한 명(예: 만료된 FCM 토큰) 실패가 나머지 수신자 발송을 막지 않게 한다. 본인 발송(FCM 네트워크
     * 호출 포함)과 보호자 목록 조회·보호자 발송은 서로 데이터 의존이 없어 전부 병렬로 처리해
     * 보호자 수·본인 발송 지연에 비례해 전체 시간이 늘어나지 않게 한다.
     */
    suspend fun notifyElderAndGuardians(
        elderUserId: String,
        title: String,
        body: String,
        logId: String? = null,
        score: Float? = null,
        fall: Boolean = false,
        data: Map<String, String>? = null
    ) {
        val elder = userRepository.findByUserId(elderUserId)

        coroutineScope {
            val elderSend = async {
                runCatching {
                    if (elder == null) throw BusinessException(ErrorCode.USER_NOT_FOUND)
                    sendNotification(elder, NotificationRequest(elderUserId, title, body, logId, score, fall, data))
                }.onFailure { e -> log.warn("알림 전송 실패 (userId: $elderUserId): ${e.message}") }
            }

            val guardianIds = guardianLinkRepository.findGuardiansOf(elderUserId)
            val elderName = elder?.name ?: elderUserId

            val guardianSends = guardianIds.map { guardianId ->
                async {
                    runCatching {
                        sendNotification(NotificationRequest(guardianId, title, "[$elderName] $body", logId, score, fall, data))
                    }.onFailure { e -> log.warn("보호자 알림 전송 실패 (guardianId: $guardianId): ${e.message}") }
                }
            }

            (listOf(elderSend) + guardianSends).awaitAll()
        }
    }

    suspend fun getNotifications(userId: String): List<NotificationLogResponse> =
        notificationRepository.findRecentByUserId(userId).map { NotificationLogResponse.from(it) }

    suspend fun markRead(userId: String, notificationId: String): NotificationLogResponse {
        val updated = notificationRepository.markRead(notificationId, userId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        return NotificationLogResponse.from(updated)
    }
}
