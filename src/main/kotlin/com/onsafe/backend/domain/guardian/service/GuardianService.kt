package com.onsafe.backend.domain.guardian.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.domain.guardian.model.dto.PairingCodeResponse
import com.onsafe.backend.domain.guardian.model.dto.WardResponse
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Duration

private const val PAIRING_CODE_TTL = 300L  // 5분

@Service
class GuardianService(
    private val guardianLinkRepository: GuardianLinkRepository,
    private val userRepository: UserRepository,
    private val redis: ReactiveStringRedisTemplate,
    private val rateLimiter: RateLimiter,
    private val verificationCodeGenerator: VerificationCodeGenerator
) {

    // pairing_code:$code 와 pairing_code_owner:$elderUserId 두 키를 한 번에 원자적으로 써서,
    // 둘 중 하나만 쓰인 채 코루틴이 취소되는 경우(한쪽 키만 살아남아 재발급 시 무효화 실패) 없앤다.
    private val issueCodeScript: RedisScript<String> = RedisScript.of(
        """
        redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
        redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
        return ARGV[2]
        """.trimIndent(),
        String::class.java
    )

    // 유저당 활성 코드는 1개만 유지 — 재발급 시 이전 코드를 먼저 무효화해
    // 캡처/전달 과정에서 노출된 옛 코드가 계속 살아있지 않게 한다.
    suspend fun issuePairingCode(elderUserId: String): PairingCodeResponse {
        val ownerKey = "pairing_code_owner:$elderUserId"
        redis.opsForValue().get(ownerKey).awaitFirstOrNull()?.let { oldCode ->
            redis.delete("pairing_code:$oldCode").awaitSingle()
        }

        var code: String
        do {
            code = verificationCodeGenerator.generate()
        } while (redis.hasKey("pairing_code:$code").awaitSingle())

        redis.execute(
            issueCodeScript,
            listOf("pairing_code:$code", ownerKey),
            listOf(elderUserId, code, PAIRING_CODE_TTL.toString())
        ).awaitSingle()

        return PairingCodeResponse(code = code, expiresInSeconds = PAIRING_CODE_TTL)
    }

    suspend fun pair(guardianUserId: String, code: String): WardResponse {
        // 코드 공간이 10^6이라 브루트포스 방지를 위한 시도 횟수 제한 — 호출자(보호자) 기준.
        rateLimiter.requireAllowed("rl:pair:$guardianUserId", limit = 10, windowSec = PAIRING_CODE_TTL)

        // GETDEL로 조회와 즉시 무효화를 원자적으로 묶는다 — 같은 코드로 pair()가 동시에 호출돼도
        // Redis가 원자적으로 처리하므로 정확히 한 요청만 elderUserId를 얻고, 나머지는 코드가 이미
        // 지워진 상태라 PAIRING_CODE_INVALID로 실패한다(GET 후 뒤늦게 DELETE하면 그 사이 창에서
        // 같은 코드가 서로 다른 보호자에게 이중으로 소비될 수 있었음).
        val elderUserId = redis.opsForValue().getAndDelete("pairing_code:$code").awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.PAIRING_CODE_INVALID)
        redis.delete("pairing_code_owner:$elderUserId").awaitSingle()

        if (elderUserId == guardianUserId) throw BusinessException(ErrorCode.SELF_PAIRING_NOT_ALLOWED)

        val elder = userRepository.findByUserId(elderUserId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        if (guardianLinkRepository.exists(guardianUserId, elderUserId)) {
            throw BusinessException(ErrorCode.PAIRING_ALREADY_EXISTS)
        }

        guardianLinkRepository.save(GuardianLink(guardianUserId = guardianUserId, elderUserId = elderUserId))

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
}
