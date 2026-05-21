package com.onsafe.backend.domain.camera.websocket

import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.domain.camera.service.CameraSessionService
import kotlinx.coroutines.reactor.mono
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CameraStreamWebSocketHandler(
    private val listenerContainer: ReactiveRedisMessageListenerContainer,
    private val cameraSessionService: CameraSessionService,
    private val jwtProvider: JwtProvider,
    private val redis: ReactiveStringRedisTemplate
) : WebSocketHandler {

    override fun handle(session: WebSocketSession): Mono<Void> {
        val userId = session.handshakeInfo.uri.path.substringAfterLast("/")
        val token = resolveToken(session)

        if (token == null || !jwtProvider.validate(token)) {
            return session.close(CloseStatus(HttpStatus.UNAUTHORIZED.value(), "Unauthorized"))
        }
        if (jwtProvider.getUserId(token) != userId) {
            return session.close(CloseStatus(HttpStatus.FORBIDDEN.value(), "Forbidden"))
        }

        // [보안] 로그아웃 블랙리스트 확인 — 폐기된 토큰으로 스트림 구독 차단
        return redis.opsForValue().get("bl:$token")
            .defaultIfEmpty("")
            .flatMap { blacklisted ->
                if (blacklisted.isNotEmpty()) {
                    session.close(CloseStatus(HttpStatus.UNAUTHORIZED.value(), "Token revoked"))
                } else {
                    startStream(session, userId)
                }
            }
    }

    private fun startStream(session: WebSocketSession, userId: String): Mono<Void> {
        val topic = ChannelTopic.of("camera:frames:$userId")
        val controlTopic = ChannelTopic.of("camera:control:$userId")
        val markedLive = AtomicBoolean(false)

        val stopSignal = listenerContainer.receive(controlTopic).next()

        val frameFlux = listenerContainer
            .receive(topic)
            .flatMap { msg ->
                // [안정성] Base64 디코딩 실패 시 해당 프레임만 skip — 전체 스트림 유지
                val bytes = runCatching { Base64.getDecoder().decode(msg.message) }.getOrNull()
                    ?: return@flatMap Mono.empty()
                val markMono: Mono<*> = if (markedLive.compareAndSet(false, true)) {
                    mono { cameraSessionService.markLive(userId) }
                } else {
                    Mono.empty<Unit>()
                }
                markMono.thenReturn(session.binaryMessage { factory -> factory.wrap(bytes) })
            }
            .takeUntilOther(stopSignal)

        // [안정성] markStandby 실패 시 onErrorComplete()로 예외를 삼켜 UndeliverableException 방지
        return session.send(frameFlux)
            .doFinally {
                mono { cameraSessionService.markStandby(userId) }
                    .onErrorComplete()
                    .subscribe()
            }
    }

    private fun resolveToken(session: WebSocketSession): String? {
        val queryToken = session.handshakeInfo.uri.query
            ?.split("&")
            ?.firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
        if (queryToken != null) return queryToken
        return session.handshakeInfo.headers.getFirst("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
    }
}
