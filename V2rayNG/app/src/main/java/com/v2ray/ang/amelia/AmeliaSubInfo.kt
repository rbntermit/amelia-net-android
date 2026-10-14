package com.v2ray.ang.amelia

import com.v2ray.ang.util.Utils

/**
 * What the subscription server tells about the account, taken from response headers:
 * Profile-Title, Subscription-Userinfo, announce, Profile-Update-Interval.
 * Persisted as JSON in MMKV, so every field must stay nullable.
 */
data class AmeliaSubInfo(
    val title: String? = null,
    val announce: String? = null,
    val upload: Long? = null,
    val download: Long? = null,
    /** Traffic limit in bytes; null or 0 = unlimited. */
    val total: Long? = null,
    /** Unix time in seconds; null or 0 = no end date. */
    val expire: Long? = null,
    val updateIntervalHours: Int? = null,
    /** When the subscription was last fetched, epoch millis. */
    val fetchedAt: Long? = null,
) {
    val used: Long get() = (upload ?: 0L) + (download ?: 0L)

    /** Remaining traffic in bytes, or null when the plan has no limit. */
    val remaining: Long?
        get() = total?.takeIf { it > 0 }?.let { (it - used).coerceAtLeast(0L) }

    val hasLimit: Boolean get() = (total ?: 0L) > 0L
    val hasExpire: Boolean get() = (expire ?: 0L) > 0L

    companion object {
        const val HEADER_TITLE = "Profile-Title"
        const val HEADER_USERINFO = "Subscription-Userinfo"
        const val HEADER_ANNOUNCE = "announce"
        const val HEADER_UPDATE_INTERVAL = "Profile-Update-Interval"

        /**
         * @param header case-insensitive header lookup (OkHttp `Response.header`)
         * @param decodeBase64 injected so JVM unit tests do not need android.util.Base64
         */
        fun fromHeaders(
            header: (String) -> String?,
            now: Long,
            decodeBase64: (String) -> String? = { Utils.decode(it).ifEmpty { null } },
        ): AmeliaSubInfo {
            val userinfo = parseUserinfo(header(HEADER_USERINFO))
            return AmeliaSubInfo(
                title = decodeHeaderValue(header(HEADER_TITLE), decodeBase64),
                announce = decodeHeaderValue(header(HEADER_ANNOUNCE), decodeBase64),
                upload = userinfo["upload"],
                download = userinfo["download"],
                total = userinfo["total"],
                expire = userinfo["expire"],
                updateIntervalHours = header(HEADER_UPDATE_INTERVAL)?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                fetchedAt = now,
            )
        }

        /** Header values may be plain text or `base64:<data>` (the de-facto standard for non-ASCII). */
        internal fun decodeHeaderValue(value: String?, decodeBase64: (String) -> String?): String? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (v.startsWith("base64:", ignoreCase = true)) {
                decodeBase64(v.substring("base64:".length).trim())?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                v
            }
        }

        /** `upload=0; download=123; total=456; expire=1700000000` → map of the numeric fields. */
        internal fun parseUserinfo(value: String?): Map<String, Long> {
            if (value.isNullOrBlank()) return emptyMap()
            return value.split(';')
                .mapNotNull { part ->
                    val kv = part.split('=', limit = 2)
                    if (kv.size != 2) return@mapNotNull null
                    val num = kv[1].trim().toDoubleOrNull()?.toLong() ?: return@mapNotNull null
                    kv[0].trim().lowercase() to num
                }
                .toMap()
        }
    }
}
