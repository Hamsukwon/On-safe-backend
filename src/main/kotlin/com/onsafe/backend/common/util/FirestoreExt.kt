package com.onsafe.backend.common.util

import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

suspend fun <T> ApiFuture<T>.await(): T = withContext(Dispatchers.IO) { get() }

fun LocalDateTime.toTimestamp(): Timestamp =
    Timestamp.of(Date.from(atZone(ZoneId.systemDefault()).toInstant()))

fun Timestamp.toLocalDateTime(): LocalDateTime =
    toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
