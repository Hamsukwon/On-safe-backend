package com.onsafe.backend.domain.fall.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.fall.model.entity.FallEvent
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class FallEventRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("fall_logs")

    suspend fun findById(id: String): FallEvent? {
        val doc = col.document(id).get().await()
        return if (doc.exists()) doc.toFallEvent() else null
    }

    suspend fun findByUserIdOrderByDetectedAtDesc(userId: String): List<FallEvent> {
        val snap = col.whereEqualTo("userId", userId)
            .orderBy("detectedAt", Query.Direction.DESCENDING)
            .get().await()
        return snap.documents.map { it.toFallEvent() }
    }

    suspend fun countByUserIdAndIsConfirmedFalse(userId: String): Long {
        val snap = col.whereEqualTo("userId", userId)
            .whereEqualTo("isConfirmed", false)
            .get().await()
        return snap.size().toLong()
    }

    suspend fun save(event: FallEvent): FallEvent {
        col.document(event.id).set(event.toMap()).await()
        return event
    }

    private fun DocumentSnapshot.toFallEvent() = FallEvent(
        id = id,
        userId = getString("userId") ?: "",
        confidence = getDouble("confidence")?.toFloat() ?: 0f,
        mediaUrl = getString("mediaUrl"),
        isConfirmed = getBoolean("isConfirmed") ?: false,
        detectedAt = getTimestamp("detectedAt")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun FallEvent.toMap() = mapOf(
        "userId" to userId,
        "confidence" to confidence,
        "mediaUrl" to mediaUrl,
        "isConfirmed" to isConfirmed,
        "detectedAt" to detectedAt.toTimestamp()
    )
}
