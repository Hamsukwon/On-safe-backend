package com.onsafe.backend.domain.camera.websocket

import com.onsafe.backend.domain.camera.service.CameraSessionService
import kotlinx.coroutines.reactor.mono
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CameraStreamWebSocketHandler(
    private val listenerContainer: ReactiveRedisMessageListenerContainer,
    private val cameraSessionService: CameraSessionService
) : WebSocketHandler {

    override fun handle(session: WebSocketSession): Mono<Void> {
        val userId = session.handshakeInfo.uri.path.substringAfterLast("/")
        val topic = ChannelTopic.of("camera:frames:$userId")
        val markedLive = AtomicBoolean(false)

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

        return session.send(frameFlux)
    }
}
