package com.onsafe.backend.common.security

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

/**
 * 모든 HTTP 요청이 컨트롤러에 도달하기 전에 실행되는 JWT 검증 필터.
 *
 * 처리 흐름:
 * 1. Authorization 헤더에서 "Bearer <token>" 추출
 * 2. 토큰 유효성 검사 (서명·만료 확인)
 * 3. 유효하면 SecurityContext에 인증 객체 주입 → 이후 @AuthenticationPrincipal로 꺼낼 수 있음
 * 4. 토큰이 없거나 무효면 그냥 다음 필터로 넘김 (예외 아님 — SecurityConfig에서 인증 요구 경로면 403 반환)
 */
@Component
class JwtAuthenticationFilter(private val jwtProvider: JwtProvider) : WebFilter {

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

        val auth = UsernamePasswordAuthenticationToken(
            userId, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        return chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
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
