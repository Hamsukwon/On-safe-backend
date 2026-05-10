package com.onsafe.backend.common.security

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
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

        return redis.opsForValue().get("bl:$token")
            .defaultIfEmpty("")
            .flatMap { blacklisted ->
                if (blacklisted.isNotEmpty()) {
                    chain.filter(exchange)
                } else {
                    val auth = UsernamePasswordAuthenticationToken(
                        jwtProvider.getUserId(token), null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                    )
                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                }
            }
    }

    private fun extractToken(exchange: ServerWebExchange): String? =
        exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
}
