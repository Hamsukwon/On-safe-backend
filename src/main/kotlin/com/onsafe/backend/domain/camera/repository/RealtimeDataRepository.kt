package com.onsafe.backend.domain.camera.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class RealtimeDataRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("realtime_data")

    suspend fun findByUserId(userId: String): RealtimeData? {
        val doc = col.document(userId).get().await()
        return if (doc.exists()) doc.toRealtimeData() else null
    }

    // merge 사용 — toMap()엔 device_seen_at이 없어서 전체 덮어쓰기(set)를 쓰면
    // touchDeviceSeenAt이 써둔 값이 이 호출로 지워진다.
    suspend fun save(data: RealtimeData): RealtimeData {
        col.document(data.userId).set(data.toMap(), SetOptions.merge()).await()
        return data
    }

    // 문서 ID가 곧 userId라 단건 delete로 충분(추가 쿼리 불필요).
    suspend fun deleteByUserId(userId: String) {
        col.document(userId).delete().await()
    }

    // score/level/updated_at은 건드리지 않고 device_seen_at만 갱신 — 하트비트가 추론 결과를
    // 덮어쓰지 않게 분리. 문서가 아직 없을 수도 있어(세션 시작 직후 첫 하트비트가 첫 추론보다
    // 먼저 도착) merge 옵션으로 upsert한다.
    suspend fun touchDeviceSeenAt(userId: String) {
        col.document(userId)
            .set(mapOf("device_seen_at" to LocalDateTime.now().toTimestamp()), SetOptions.merge())
            .await()
    }

    private fun DocumentSnapshot.toRealtimeData() = RealtimeData(
        userId = id,
        score = getDouble("score")?.toFloat() ?: 0f,
        level = getString("level") ?: "정상",
        updatedAt = getTimestamp("updated_at")?.toLocalDateTime() ?: LocalDateTime.now(),
        deviceSeenAt = getTimestamp("device_seen_at")?.toLocalDateTime()
    )

    private fun RealtimeData.toMap() = mapOf(
        "score" to score,
        "level" to level,
        "updated_at" to LocalDateTime.now().toTimestamp()
    )
}
