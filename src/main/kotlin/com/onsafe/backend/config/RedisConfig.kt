package com.onsafe.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer

@Configuration
class RedisConfig {

    // 카메라 프레임(Base64 문자열)을 Redis pub/sub으로 구독하기 위한 리스너 컨테이너
    @Bean
    fun reactiveRedisMessageListenerContainer(
        factory: ReactiveRedisConnectionFactory
    ): ReactiveRedisMessageListenerContainer =
        ReactiveRedisMessageListenerContainer(factory)
}
