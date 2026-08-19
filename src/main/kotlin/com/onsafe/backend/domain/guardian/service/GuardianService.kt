package com.onsafe.backend.domain.guardian.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.domain.guardian.model.dto.PairingCodeResponse
import com.onsafe.backend.domain.guardian.model.dto.WardResponse
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration

private const val PAIRING_CODE_TTL = 300L  // 5분

@Service
class GuardianService(
    private val guardianLinkRepository: GuardianLinkRepository,
    private val userRepository: UserRepository,
    private val redis: ReactiveStringRedisTemplate,
    private val rateLimiter: RateLimiter
) {

    private val secureRandom = SecureRandom()

    // 유저당 활성 코드는 1개만 유지 — 재발급 시 이전 코드를 먼저 무효화해
    // 캡처/전달 과정에서 노출된 옛 코드가 계속 살아있지 않게 한다.
    suspend fun issuePairingCode(elderUserId: String): PairingCodeResponse {
        val ownerKey = "pairing_code_owner:$elderUserId"
        redis.opsForValue().get(ownerKey).awaitFirstOrNull()?.let { oldCode ->
            redis.delete("pairing_code:$oldCode").awaitSingle()
        }

        var code: String
        do {
            code = generatePairingCode()
        } while (redis.hasKey("pairing_code:$code").awaitSingle())

        val ttl = Duration.ofSeconds(PAIRING_CODE_TTL)
        redis.opsForValue().set("pairing_code:$code", elderUserId, ttl).awaitSingle()
        redis.opsForValue().set(ownerKey, code, ttl).awaitSingle()

        return PairingCodeResponse(code = code, expiresInSeconds = PAIRING_CODE_TTL)
    }

    suspend fun pair(guardianUserId: String, code: String): WardResponse {
        // 코드 공간이 10^6이라 브루트포스 방지를 위한 시도 횟수 제한 — 호출자(보호자) 기준.
        rateLimiter.requireAllowed("rl:pair:$guardianUserId", limit = 10, windowSec = PAIRING_CODE_TTL)

        val key = "pairing_code:$code"
        val elderUserId = redis.opsForValue().get(key).awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.PAIRING_CODE_INVALID)

        if (elderUserId == guardianUserId) throw BusinessException(ErrorCode.SELF_PAIRING_NOT_ALLOWED)

        val elder = userRepository.findByUserId(elderUserId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        if (guardianLinkRepository.exists(guardianUserId, elderUserId)) {
            throw BusinessException(ErrorCode.PAIRING_ALREADY_EXISTS)
        }

        guardianLinkRepository.save(GuardianLink(guardianUserId = guardianUserId, elderUserId = elderUserId))

        // 1회용 — 연결 성공 후 코드를 즉시 폐기해 재사용을 막는다.
        redis.delete(key).awaitSingle()
        redis.delete("pairing_code_owner:$elderUserId").awaitSingle()

        return WardResponse.from(elder)
    }

    suspend fun getWards(guardianUserId: String): List<WardResponse> =
        guardianLinkRepository.findWardsOf(guardianUserId)
            .mapNotNull { link -> userRepository.findByUserId(link.elderUserId)?.let(WardResponse::from) }

    // 어느 쪽(보호자/피보호자)이 호출했는지 몰라도 되도록 양방향 문서 ID를 모두 시도한다.
    suspend fun unpair(userId: String, counterpartUserId: String) {
        val deleted = guardianLinkRepository.delete(userId, counterpartUserId) ||
            guardianLinkRepository.delete(counterpartUserId, userId)
        if (!deleted) throw BusinessException(ErrorCode.PAIRING_NOT_FOUND)
    }

    // 페어링 코드는 타인 계정에 대한 읽기 권한을 부여하는 인증 수단이므로 예측 가능한
    // kotlin.random.Random 대신 SecureRandom을 사용한다(AuthService.generateVerificationCode와 동일 이유).
    private fun generatePairingCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))
}
