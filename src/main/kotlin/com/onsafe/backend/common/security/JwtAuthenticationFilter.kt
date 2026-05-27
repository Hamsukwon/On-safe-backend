package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.ErrorCode
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

        val validationError = jwtProvider.getValidationError(token)
        if (validationError != null) return writeErrorResponse(exchange, validationError)

        val userId = jwtProvider.getUserId(token)

        // 블랙리스트 체크를 경로 체크보다 먼저 수행 — 폐기 토큰은 401, 경로 불일치는 403
        return redis.opsForValue().get("bl:$token")
            .defaultIfEmpty("")
            .flatMap { blacklisted ->
                if (blacklisted.isNotEmpty()) {
                    writeErrorResponse(exchange, ErrorCode.INVALID_TOKEN)
                } else {
                    // WebSocket 경로(/ws/camera/{userId}): 토큰 userId와 경로 userId 불일치 시 403 차단
                    val path = exchange.request.path.value()
                    if (path.startsWith("/ws/camera/")) {
                        val pathUserId = path.substringAfterLast("/")
                        if (userId != pathUserId) {
                            exchange.response.statusCode = HttpStatus.FORBIDDEN
                            return@flatMap exchange.response.setComplete()
                        }
                    }
                    val auth = UsernamePasswordAuthenticationToken(
                        userId, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                    )
                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                }
            }
    }

    private fun writeErrorResponse(exchange: ServerWebExchange, errorCode: ErrorCode): Mono<Void> {
        val response = exchange.response
        response.statusCode = errorCode.status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"success":false,"message":"${errorCode.message}","data":null}"""
        val buffer = response.bufferFactory().wrap(body.toByteArray(Charsets.UTF_8))
        return response.writeWith(Mono.just(buffer))
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
