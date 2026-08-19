package com.onsafe.backend.domain.guardian.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Base64

@Repository
class GuardianLinkRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("guardian_links")

    // 문서 ID를 guardianUserId_elderUserId 복합키로 고정해 동일 관계 중복 저장을 원천 차단한다.
    // userId 자체에 글자 수 제한이 없어 "_"를 그대로 구분자로 쓰면 예를 들어
    // (guardian="a_b", elder="c")와 (guardian="a", elder="b_c")가 똑같이 "a_b_c"로 충돌한다.
    // 각 파트를 URL-safe Base64(패딩 없음, 알파벳에 ":" 없음)로 인코딩한 뒤 ":"로 이어붙이면
    // 구분자가 인코딩 결과에 절대 나타나지 않아 충돌이 구조적으로 불가능하다.
    private val idEncoder = Base64.getUrlEncoder().withoutPadding()

    private fun docId(guardianUserId: String, elderUserId: String) =
        "${idEncoder.encodeToString(guardianUserId.toByteArray())}:${idEncoder.encodeToString(elderUserId.toByteArray())}"

    suspend fun exists(guardianUserId: String, elderUserId: String): Boolean =
        col.document(docId(guardianUserId, elderUserId)).get().await().exists()

    suspend fun save(link: GuardianLink) {
        col.document(docId(link.guardianUserId, link.elderUserId)).set(link.toMap()).await()
    }

    suspend fun findWardsOf(guardianUserId: String): List<GuardianLink> =
        col.whereEqualTo("guardian_user_id", guardianUserId).get().await().documents.map { it.toLink() }

    suspend fun findGuardiansOf(elderUserId: String): List<String> =
        col.whereEqualTo("elder_user_id", elderUserId).get().await().documents
            .mapNotNull { it.getString("guardian_user_id") }

    suspend fun delete(guardianUserId: String, elderUserId: String): Boolean {
        val ref = col.document(docId(guardianUserId, elderUserId))
        if (!ref.get().await().exists()) return false
        ref.delete().await()
        return true
    }

    // 계정 탈퇴 시 이 유저가 보호자·피보호자 어느 쪽으로 맺은 관계든 전부 정리한다.
    suspend fun deleteAllInvolving(userId: String) {
        val asGuardian = col.whereEqualTo("guardian_user_id", userId).get().await().documents
        val asElder = col.whereEqualTo("elder_user_id", userId).get().await().documents
        (asGuardian + asElder).forEach { it.reference.delete().await() }
    }

    private fun DocumentSnapshot.toLink() = GuardianLink(
        guardianUserId = getString("guardian_user_id") ?: "",
        elderUserId = getString("elder_user_id") ?: "",
        createdAt = getTimestamp("created_at")?.toLocalDateTime() ?: LocalDateTime.now(),
    )

    private fun GuardianLink.toMap() = mapOf(
        "guardian_user_id" to guardianUserId,
        "elder_user_id" to elderUserId,
        "created_at" to createdAt.toTimestamp(),
    )
}
