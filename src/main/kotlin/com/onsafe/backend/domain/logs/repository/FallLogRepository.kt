package com.onsafe.backend.domain.logs.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import com.onsafe.backend.domain.logs.model.entity.FallLog
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDateTime

@Repository
class FallLogRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("fall_logs")

    suspend fun findRecentByUserId(userId: String, level: String? = null): List<FallLog> {
        val all = col.whereEqualTo("user_id", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(100)
            .get().await().documents.map { it.toFallLog() }
        return if (level != null) all.filter { it.matchesLevel(level) } else all
    }

    suspend fun countByUserId(userId: String): Map<String, Int> {
        val all = col.whereEqualTo("user_id", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(100)
            .get().await().documents.map { it.toFallLog() }
        return mapOf(
            "전체" to all.size,
            "위험" to all.count { it.matchesLevel("위험") },
            "주의" to all.count { it.matchesLevel("주의") }
        )
    }

    private fun FallLog.matchesLevel(level: String) = when (level) {
        "위험" -> score > RiskLevel.DANGER_THRESHOLD
        "주의" -> score > RiskLevel.WARNING_THRESHOLD && score <= RiskLevel.DANGER_THRESHOLD
        else   -> true
    }

    suspend fun findByLogIdAndUserId(logId: String, userId: String): FallLog? =
        getDocIfOwned(logId, userId)?.toFallLog()

    suspend fun save(log: FallLog): FallLog {
        col.document(log.logId).set(log.toMap()).await()
        return log
    }

    suspend fun confirmByLogIdAndUserId(logId: String, userId: String): FallLog? {
        val doc = getDocIfOwned(logId, userId) ?: return null
        col.document(logId).update("is_confirmed", true).await()
        return doc.toFallLog().copy(isConfirmed = true)
    }

    suspend fun setVideoUrlByLogIdAndUserId(logId: String, userId: String, videoUrl: String): FallLog? {
        val doc = getDocIfOwned(logId, userId) ?: return null
        col.document(logId).update("video_url", videoUrl).await()
        return doc.toFallLog().copy(videoUrl = videoUrl)
    }

    suspend fun deleteByLogIdAndUserId(logId: String, userId: String): Long {
        getDocIfOwned(logId, userId) ?: return 0L
        col.document(logId).delete().await()
        return 1L
    }

    /** 미확인 위험(위험) 레벨 이벤트 조회 — 에스컬레이션 스케줄러 후보 목록.
     * Firestore 복합 인덱스(is_confirmed ASC, score ASC) 필요.
     * timestamp 하한은 range 필터 중복 제약(Firestore는 필드당 range 필터 1개만 허용) 때문에 인메모리에서 거른다. */
    suspend fun findUnconfirmedDangerLogs(after: LocalDateTime): List<FallLog> =
        col.whereEqualTo("is_confirmed", false)
            .whereGreaterThan("score", RiskLevel.DANGER_THRESHOLD)
            .limit(500)
            .get().await().documents
            .map { it.toFallLog() }
            .filter { it.timestamp >= after }

    /** video_url이 비어있는 위험(위험) 레벨 로그 조회 — 정합성 보정 잡(#16) 후보 목록.
     * 단일 동등 필터(video_url == null)만 사용해 별도 복합 인덱스 없이 동작한다.
     * score/fall/timestamp 조건은 인메모리에서 거른다(주의 레벨은 애초에 영상을 제공하지 않으므로 제외). */
    suspend fun findMissingVideoLogs(after: LocalDateTime): List<FallLog> =
        col.whereEqualTo("video_url", null)
            .limit(500)
            .get().await().documents
            .map { it.toFallLog() }
            .filter { (it.score > RiskLevel.DANGER_THRESHOLD || it.fall) && it.timestamp >= after }

    /** 리마인더 발송 직전 재확인 + 원자적 클레임(트랜잭션).
     * 스케줄러의 최초 조회와 실제 발송 사이에 보호자가 방금 확인했거나 다른 스케줄러 실행이
     * 먼저 처리한 경우를 트랜잭션 내부에서 다시 읽어 걸러낸다 — 경합으로 인한 중복/오발송 방지. */
    suspend fun claimReminder(logId: String, interval: Duration): FallLog? {
        val docRef = col.document(logId)
        return firestore.runTransaction { tx ->
            val snapshot = tx.get(docRef).get()
            if (!snapshot.exists()) return@runTransaction null
            val current = snapshot.toFallLog()
            if (current.isConfirmed) return@runTransaction null

            val base = current.lastReminderAt ?: current.timestamp
            if (Duration.between(base, LocalDateTime.now()) < interval) return@runTransaction null

            val now = LocalDateTime.now()
            tx.update(docRef, "last_reminder_at", now.toTimestamp())
            current.copy(lastReminderAt = now)
        }.await()
    }

    private suspend fun getDocIfOwned(logId: String, userId: String): DocumentSnapshot? {
        val doc = col.document(logId).get().await()
        return if (doc.exists() && doc.getString("user_id") == userId) doc else null
    }

    private fun DocumentSnapshot.toFallLog() = FallLog(
        logId = id,
        deviceId = getString("device_id") ?: "",
        userId = getString("user_id") ?: "",
        score = getDouble("score")?.toFloat() ?: 0f,
        fall = getBoolean("fall") ?: false,
        isConfirmed = getBoolean("is_confirmed") ?: false,
        videoUrl = getString("video_url"),
        lastReminderAt = getTimestamp("last_reminder_at")?.toLocalDateTime(),
        timestamp = getTimestamp("timestamp")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun FallLog.toMap() = mapOf(
        "device_id" to deviceId,
        "user_id" to userId,
        "score" to score,
        "fall" to fall,
        "is_confirmed" to isConfirmed,
        "video_url" to videoUrl,
        "last_reminder_at" to lastReminderAt?.toTimestamp(),
        "timestamp" to timestamp.toTimestamp()
    )
}
