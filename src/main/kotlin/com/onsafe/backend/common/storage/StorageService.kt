package com.onsafe.backend.common.storage

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

@Service
class StorageService(
    @Value("\${firebase.credentials:serviceAccountKey.json}") private val credentialsPath: String,
    @Value("\${firebase.storage.bucket:}") private val bucketName: String
) {
    private val gcsStorage: Storage by lazy {
        val credentials = ServiceAccountCredentials.fromStream(FileInputStream(credentialsPath))
        StorageOptions.newBuilder()
            .setCredentials(credentials)
            .build()
            .service
    }

    /** GCS 경로를 받아 V4 signed URL을 발급한다 (기본 1시간 유효). */
    suspend fun generateSignedUrl(imageUrl: String, expiryHours: Long = 1L): String {
        return withContext(Dispatchers.IO) {
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, imageUrl)).build()
            gcsStorage.signUrl(
                blobInfo,
                expiryHours,
                TimeUnit.HOURS,
                Storage.SignUrlOption.withV4Signature()
            ).toString()
        }
    }
}
