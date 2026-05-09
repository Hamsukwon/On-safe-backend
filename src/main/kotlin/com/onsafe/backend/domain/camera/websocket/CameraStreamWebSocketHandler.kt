package com.onsafe.backend.domain.camera.websocket

import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.domain.camera.service.CameraSessionService
import kotlinx.coroutines.reactor.mono
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CameraStreamWebSocketHandler(
    private val listenerContainer: ReactiveRedisMessageListenerContainer,
    private val cameraSessionService: CameraSessionService,
    private val jwtProvider: JwtProvider
) : WebSocketHandler {

    override fun handle(session: WebSocketSession): Mono<Void> {
        val userId = session.handshakeInfo.uri.path.substringAfterLast("/")

        // WebSocket은 HTTP 필터 체인을 통과하지 않으므로 핸들러에서 직접 JWT 검증
        // 토큰은 쿼리 파라미터 token= 또는 Authorization 헤더 중 하나로 전달
        val token = resolveToken(session)
        if (token == null || !jwtProvider.validate(token)) {
            return session.close(org.springframework.web.reactive.socket.CloseStatus(HttpStatus.UNAUTHORIZED.value(), "Unauthorized"))
        }

        // 토큰의 userId와 경로의 userId가 일치하는지 확인 → 타인 스트림 구독 차단
        val tokenUserId = jwtProvider.getUserId(token)
        if (tokenUserId != userId) {
            return session.close(org.springframework.web.reactive.socket.CloseStatus(HttpStatus.FORBIDDEN.value(), "Forbidden"))
        }

        val topic = ChannelTopic.of("camera:frames:$userId")
        val controlTopic = ChannelTopic.of("camera:control:$userId")
        val markedLive = AtomicBoolean(false)

        // stopSession() 호출 시 STOP 신호가 발행되면 frameFlux를 완료시켜 WebSocket 연결 종료
        val stopSignal = listenerContainer.receive(controlTopic).next()

        // Redis pub/sub은 String만 지원 → 프레임은 Base64 인코딩 후 발행, 여기서 디코딩
        val frameFlux = listenerContainer
            .receive(topic)
            .flatMap { msg ->
                val bytes = Base64.getDecoder().decode(msg.message)
                val markMono: Mono<*> = if (markedLive.compareAndSet(false, true)) {
                    mono { cameraSessionService.markLive(userId) }
                } else {
                    Mono.empty<Unit>()
                }
                markMono.thenReturn(
                    session.binaryMessage { factory -> factory.wrap(bytes) }
                )
            }
            .takeUntilOther(stopSignal)

        // 연결 종료(정상/오류/STOP 신호 모두) 시 세션 상태를 STANDBY로 복구
        return session.send(frameFlux)
            .doFinally { mono { cameraSessionService.markStandby(userId) }.subscribe() }
    }

    private fun resolveToken(session: WebSocketSession): String? {
        // 1순위: 쿼리 파라미터 (?token=xxx) — 대부분의 WebSocket 클라이언트가 헤더 설정 불가
        val queryToken = session.handshakeInfo.uri.query
            ?.split("&")
            ?.firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
        if (queryToken != null) return queryToken

        // 2순위: Authorization 헤더 (Bearer xxx)
        return session.handshakeInfo.headers.getFirst("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
    }
}
