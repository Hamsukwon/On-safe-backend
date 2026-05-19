package com.onsafe.backend.config

import com.onsafe.backend.domain.camera.websocket.CameraStreamWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebSocketConfig(private val cameraStreamHandler: CameraStreamWebSocketHandler) {

    @Bean
    fun webSocketHandlerMapping(): HandlerMapping {
        val map = SimpleUrlHandlerMapping()
        map.urlMap = mapOf("/ws/camera/**" to cameraStreamHandler)
        map.order = 1
        return map
    }

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()
}
