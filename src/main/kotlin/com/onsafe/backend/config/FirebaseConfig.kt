package com.onsafe.backend.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream

@Configuration
class FirebaseConfig {

    @Value("\${firebase.credentials:serviceAccountKey.json}")
    private lateinit var credentialsPath: String

    @Bean
    fun firestore(): Firestore {
        if (FirebaseApp.getApps().isEmpty()) {
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(FileInputStream(credentialsPath)))
                .build()
            FirebaseApp.initializeApp(options)
        }
        return FirestoreClient.getFirestore()
    }
}
