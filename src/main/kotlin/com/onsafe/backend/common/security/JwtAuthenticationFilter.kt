package com.onsafe.backend.common.security

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class JwtAuthenticationFilter(
    private val jwtProvider: JwtProvider,
    private val redis: ReactiveStringRedisTemplate
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val token = extractToken(exchange) ?: return chain.filter(exchange)

        if (!jwtProvider.validate(token)) return chain.filter(exchange)

        val userId = jwtProvider.getUserId(token)

        // WebSocket 경로(/ws/camera/{userId}): 토큰 userId와 경로 userId 불일치 시 HTTP 레벨에서 403 차단
        val path = exchange.request.path.value()
        if (path.startsWith("/ws/camera/")) {
            val pathUserId = path.substringAfterLast("/")
            if (userId != pathUserId) {
                exchange.response.statusCode = HttpStatus.FORBIDDEN
                return exchange.response.setComplete()
            }
        }

        return redis.opsForValue().get("bl:$token")
            .defaultIfEmpty("")
            .flatMap { blacklisted ->
                if (blacklisted.isNotEmpty()) {
                    chain.filter(exchange)
                } else {
                    val auth = UsernamePasswordAuthenticationToken(
                        userId, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                    )
                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                }
            }
    }

    // Authorization 헤더 우선, 없으면 ?token= 쿼리 파라미터 (WebSocket 업그레이드 요청용)
    private fun extractToken(exchange: ServerWebExchange): String? {
        val headerToken = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
        if (headerToken != null) return headerToken
        return exchange.request.queryParams.getFirst("token")
    }
}
