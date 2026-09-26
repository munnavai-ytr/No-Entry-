package com.example.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class SubscriptionRecord(
    @SerialName("id") val id: Long? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("expiration_timestamp") val expirationTimestamp: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    val effectiveExpiresAt: String?
        get() = expiresAt ?: expirationTimestamp

    fun getExpirationInstant(): Instant? {
        val raw = effectiveExpiresAt ?: return null
        return try {
            Instant.parse(raw)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME).toInstant()
            } catch (_: Exception) {
                try {
                    val num = raw.toLongOrNull() ?: return null
                    if (num > 100_000_000_000L) {
                        Instant.ofEpochMilli(num)
                    } else {
                        Instant.ofEpochSecond(num)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun isSubscriptionActive(): Boolean {
        val expiry = getExpirationInstant() ?: return false
        return expiry.isAfter(Instant.now())
    }
}

@Serializable
data class SubscriptionInsert(
    @SerialName("device_id") val deviceId: String,
    @SerialName("expires_at") val expiresAt: String
)

data class CountdownTime(
    val days: Long = 0,
    val hours: Long = 0,
    val minutes: Long = 0,
    val seconds: Long = 0,
    val totalSecondsRemaining: Long = 0,
    val isExpired: Boolean = false
) {
    val formattedDays: String get() = String.format("%02d", days.coerceAtLeast(0))
    val formattedHours: String get() = String.format("%02d", hours.coerceAtLeast(0))
    val formattedMinutes: String get() = String.format("%02d", minutes.coerceAtLeast(0))
    val formattedSeconds: String get() = String.format("%02d", seconds.coerceAtLeast(0))
}
