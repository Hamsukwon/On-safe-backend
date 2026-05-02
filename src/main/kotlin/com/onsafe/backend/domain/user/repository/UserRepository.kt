package com.onsafe.backend.domain.user.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.user.model.entity.User
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class UserRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("users")

    suspend fun findByUserId(userId: String): User? {
        val doc = col.document(userId).get().await()
        return if (doc.exists()) doc.toUser() else null
    }

    suspend fun findByMail(mail: String): User? {
        val snap = col.whereEqualTo("mail", mail).get().await()
        return snap.documents.firstOrNull()?.toUser()
    }

    suspend fun existsByUserId(userId: String): Boolean =
        col.document(userId).get().await().exists()

    suspend fun existsByMail(mail: String): Boolean =
        col.whereEqualTo("mail", mail).get().await().isEmpty.not()

    suspend fun save(user: User): User {
        col.document(user.userId).set(user.toMap()).await()
        return user
    }

    suspend fun deleteByUserId(userId: String) {
        col.document(userId).delete().await()
    }

    private fun DocumentSnapshot.toUser() = User(
        userId = id,
        password = getString("password") ?: "",
        name = getString("name") ?: "",
        phone = getString("phone") ?: "",
        wardName = getString("ward_name") ?: "",
        mail = getString("mail") ?: "",
        address = getString("address"),
        cameraUrl = getString("camera_url"),
        fcmToken = getString("fcm_token"),
        createdAt = getTimestamp("created_at")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun User.toMap() = mapOf(
        "password" to password,
        "name" to name,
        "phone" to phone,
        "ward_name" to wardName,
        "mail" to mail,
        "address" to address,
        "camera_url" to cameraUrl,
        "fcm_token" to fcmToken,
        "created_at" to createdAt.toTimestamp()
    )
}
