package com.v2ray.ang.amelia

import com.v2ray.ang.handler.MmkvManager

/**
 * Reserve locations ("🆘 Резервный — только Telegram"): they pass only Telegram, so people
 * with an expired subscription can still reach the bot and pay. Everything else is dropped
 * there, so the usual generate_204 check always fails on them although they work as intended.
 *
 * - they are checked with a request to Telegram ([TEST_URL]); any HTTP answer means "works";
 * - automatic choice and failover take them only when no regular location works.
 */
object AmeliaReserve {

    const val TEST_URL = "https://api.telegram.org/"

    /** Recognised by name: "только Telegram" or "Резервный", any letter case. */
    fun isReserve(name: String?): Boolean =
        name != null && (name.contains("только telegram", ignoreCase = true) || name.contains("резервный", ignoreCase = true))

    /** URL for the "does it work" request through a location named [name]. */
    fun testUrl(name: String?, default: String): String = if (isReserve(name)) TEST_URL else default

    /**
     * The core accepts only 200/204 and reports any other status as "invalid status: …";
     * for Telegram that is still an answer, i.e. the location works.
     */
    fun answeredHttp(error: String?): Boolean = error?.contains("invalid status", ignoreCase = true) == true

    /**
     * Result of a check through a reserve location: the core's delay when it accepted the
     * answer, otherwise [elapsedMs] when Telegram answered with another status (the core makes
     * two attempts, so half of the time spent). Not reserve: unchanged.
     */
    fun interpret(reserve: Boolean, delayMs: Long, error: String?, elapsedMs: Long): Pair<Long, String?> = when {
        !reserve || delayMs >= 0 -> delayMs to error
        answeredHttp(error) -> (elapsedMs / 2).coerceAtLeast(1L) to null
        else -> delayMs to error
    }

    /**
     * Next location for failover: regular ones after [current] (wrapping) first; a reserve one
     * only when every regular location was tried. Null when everything was tried.
     */
    fun next(order: List<String>, current: String?, tried: Set<String>, reserves: Set<String>): String? {
        if (order.isEmpty()) return null
        val start = order.indexOf(current)
        val ring = (1..order.size).map { order[Math.floorMod(start + it, order.size)] }
        return ring.firstOrNull { it !in tried && it !in reserves }
            ?: ring.firstOrNull { it !in tried && it in reserves }
    }

    /** Location to pick automatically: the first regular one, a reserve one only if there is nothing else. */
    fun firstChoice(locations: List<AmeliaManager.Location>): AmeliaManager.Location? =
        locations.firstOrNull { !isReserve(it.name) } ?: locations.firstOrNull()
}

/** Set while the daemon fell back to a reserve location because no regular one worked. */
object AmeliaReserveState {
    private const val PREF_AUTO = "amelia_reserve_auto"

    fun isAuto(): Boolean = MmkvManager.decodeSettingsBool(PREF_AUTO, false)

    fun setAuto(auto: Boolean) = MmkvManager.encodeSettings(PREF_AUTO, auto)

    /** Guids of the reserve locations of our subscription. */
    fun reserveGuids(): Set<String> =
        MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).filter { guid ->
            AmeliaReserve.isReserve(MmkvManager.decodeServerConfig(guid)?.remarks)
        }.toSet()

    fun isReserveGuid(guid: String?): Boolean =
        guid != null && AmeliaReserve.isReserve(MmkvManager.decodeServerConfig(guid)?.remarks)
}
