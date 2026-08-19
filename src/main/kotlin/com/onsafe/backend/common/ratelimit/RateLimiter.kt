package com.onsafe.backend.common.ratelimit

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis INCR + EXPIRE 기반 고정창(fixed-window) 리미터.
 * 첫 요청 시에만 TTL을 걸어, 창이 지나면 자동으로 카운트가 초기화된다.
 */
@Component
class RateLimiter(private val redis: ReactiveStringRedisTemplate) {

    /**
     * @return true = 허용, false = 초과. Redis 장애 시 예외를 그대로 전파(fail-closed)한다 —
     * 로그인/코드 발송 같은 보안 관문에서 조용히 통과시키는 것보다 명시적 500이 안전.
     */
    suspend fun allow(key: String, limit: Long, windowSec: Long): Boolean {
        val count = redis.opsForValue().increment(key).awaitSingle()
        if (count == 1L) {
            redis.expire(key, Duration.ofSeconds(windowSec)).awaitSingle()
        }
        return count <= limit
    }
}