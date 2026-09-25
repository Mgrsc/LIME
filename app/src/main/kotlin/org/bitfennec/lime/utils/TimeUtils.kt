package org.bitfennec.lime.utils

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.Locale

/**
 * Time utility functions.
 */
object TimeUtils {

    fun iso8601UTCDateTime(timeMillis: Long? = null): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
        return formatter.format(Instant.ofEpochMilli(timeMillis ?: System.currentTimeMillis()))
    }
}
