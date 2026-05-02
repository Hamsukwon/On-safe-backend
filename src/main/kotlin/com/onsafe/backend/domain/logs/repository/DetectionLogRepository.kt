package com.onsafe.backend.domain.logs.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.logs.model.entity.DetectionLog
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class DetectionLogRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("fall_logs")

    suspend fun findRecentByUserId(userId: String): List<DetectionLog> {
        val snap = col.whereEqualTo("user_id", userId).get().await()
        return snap.documents
            .map { it.toDetectionLog() }
            .sortedByDescending { it.timestamp }
    }

    suspend fun findByLogIdAndUserId(logId: String, userId: String): DetectionLog? {
        val doc = col.document(logId).get().await()
        if (!doc.exists()) return null
        val log = doc.toDetectionLog()
        return if (log.userId == userId) log else null
    }

    suspend fun save(log: DetectionLog): DetectionLog {
        col.document(log.logId).set(log.toMap()).await()
        return log
    }

    suspend fun deleteByLogIdAndUserId(logId: String, userId: String): Long {
        val doc = col.document(logId).get().await()
        if (!doc.exists() || doc.getString("user_id") != userId) return 0L
        col.document(logId).delete().await()
        return 1L
    }

    private fun DocumentSnapshot.toDetectionLog() = DetectionLog(
        logId = id,
        deviceId = getString("device_id") ?: "",
        userId = getString("user_id") ?: "",
        score = getDouble("score")?.toFloat() ?: 0f,
        fall = getBoolean("fall") ?: false,
        isConfirmed = getBoolean("is_confirmed") ?: false,
        timestamp = getTimestamp("timestamp")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun DetectionLog.toMap() = mapOf(
        "device_id" to deviceId,
        "user_id" to userId,
        "score" to score,
        "fall" to fall,
        "is_confirmed" to isConfirmed,
        "timestamp" to timestamp.toTimestamp()
    )
}
