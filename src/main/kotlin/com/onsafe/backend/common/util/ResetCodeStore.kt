package com.onsafe.backend.common.util

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class ResetCodeStore {

    private data class Entry(val code: String, val expiresAt: Instant)

    private val store = ConcurrentHashMap<String, Entry>()

    fun save(userId: String, code: String, ttlSeconds: Long = 180L) {
        store[userId] = Entry(code, Instant.now().plusSeconds(ttlSeconds))
    }

    fun verify(userId: String, code: String): Boolean {
        val entry = store[userId] ?: return false
        if (Instant.now().isAfter(entry.expiresAt)) {
            store.remove(userId)
            return false
        }
        return entry.code == code
    }

    fun remove(userId: String) {
        store.remove(userId)
    }
}
