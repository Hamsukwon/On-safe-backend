package com.onsafe.backend.domain.camera.repository

import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import org.springframework.stereotype.Repository

@Repository
class DeviceRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("devices")

    suspend fun findCameraUrlByUserId(userId: String): String? {
        val snap = col.whereEqualTo("user_id", userId).get().await()
        return snap.documents.firstOrNull()?.getString("camera_url")
    }

    suspend fun findUserIdByDeviceId(deviceId: String): String? {
        val doc = col.document(deviceId).get().await()
        return if (doc.exists()) doc.getString("user_id") else null
    }

    suspend fun updateCameraUrl(deviceId: String, cameraUrl: String) {
        col.document(deviceId).set(mapOf("camera_url" to cameraUrl), com.google.cloud.firestore.SetOptions.merge()).await()
    }
}
