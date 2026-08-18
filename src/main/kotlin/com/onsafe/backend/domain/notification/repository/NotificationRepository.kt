package com.onsafe.backend.domain.notification.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.notification.model.entity.Notification
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class NotificationRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("notifications")

    suspend fun save(notification: Notification): Notification {
        val ref = col.document()
        val saved = notification.copy(notificationId = ref.id)
        ref.set(saved.toMap()).await()
        return saved
    }

    // Firestore 복합 인덱스(user_id ASC, created_at DESC) 필요 — firestore.indexes.json 참고.
    suspend fun findRecentByUserId(userId: String, limit: Int = 50): List<Notification> =
        col.whereEqualTo("user_id", userId)
            .orderBy("created_at", Query.Direction.DESCENDING)
            .limit(limit)
            .get().await().documents.map { it.toNotification() }

    suspend fun deleteByUserId(userId: String) {
        val docs = col.whereEqualTo("user_id", userId).get().await().documents
        docs.forEach { it.reference.delete().await() }
    }

    suspend fun markRead(notificationId: String, userId: String): Notification? {
        val doc = col.document(notificationId).get().await()
        if (!doc.exists() || doc.getString("user_id") != userId) return null
        doc.reference.update("is_read", true).await()
        return doc.toNotification().copy(isRead = true)
    }

    private fun DocumentSnapshot.toNotification() = Notification(
        notificationId = id,
        userId = getString("user_id") ?: "",
        title = getString("title") ?: "",
        body = getString("body") ?: "",
        logId = getString("log_id"),
        score = getDouble("score")?.toFloat(),
        fall = getBoolean("fall") ?: false,
        isRead = getBoolean("is_read") ?: false,
        createdAt = getTimestamp("created_at")?.toLocalDateTime() ?: LocalDateTime.now(),
    )

    private fun Notification.toMap() = mapOf(
        "user_id" to userId,
        "title" to title,
        "body" to body,
        "log_id" to logId,
        "score" to score,
        "fall" to fall,
        "is_read" to isRead,
        "created_at" to createdAt.toTimestamp(),
    )
}
