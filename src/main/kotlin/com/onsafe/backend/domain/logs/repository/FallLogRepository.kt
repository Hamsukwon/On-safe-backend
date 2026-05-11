package com.onsafe.backend.domain.logs.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.logs.model.entity.FallLog
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class FallLogRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("fall_logs")

    suspend fun findRecentByUserId(userId: String): List<FallLog> {
        val snap = col.whereEqualTo("user_id", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get().await()
        return snap.documents.map { it.toFallLog() }
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

    suspend fun deleteByLogIdAndUserId(logId: String, userId: String): Long {
        getDocIfOwned(logId, userId) ?: return 0L
        col.document(logId).delete().await()
        return 1L
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
        timestamp = getTimestamp("timestamp")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun FallLog.toMap() = mapOf(
        "device_id" to deviceId,
        "user_id" to userId,
        "score" to score,
        "fall" to fall,
        "is_confirmed" to isConfirmed,
        "timestamp" to timestamp.toTimestamp()
    )
}
