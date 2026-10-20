package com.v2ray.ang.amelia

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/**
 * Rules for automatic choice and switching (1.4.0), kept pure so they can be tested.
 * See docs/LOGIC.md for the whole picture.
 */
object AmeliaPolicy {

    /** Settings → "Автопереключение". */
    enum class Mode { ALWAYS, UNLESS_MANUAL, OFF }

    const val PREF_MODE = "amelia_autoswitch_mode"
    val DEFAULT_MODE = Mode.UNLESS_MANUAL

    /** A location the person chose stays this long after they came back to it. */
    const val RESPECT_RETURN_MS = 10L * 60 * 1000
    /** Coming back to a location we left within this time counts as "вернулся". */
    const val RETURN_WINDOW_MS = 30L * 60 * 1000

    /** Manual choice: failures in a row and the time they must span before it counts as broken. */
    const val MANUAL_FAILURES = 3
    const val MANUAL_SPAN_MS = 30_000L
    /** Automatic choice: two failures in a row (one when no data came back at all). */
    const val AUTO_FAILURES = 2

    fun mode(): Mode = MmkvManager.decodeSettingsString(PREF_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: DEFAULT_MODE

    fun setMode(m: Mode) = MmkvManager.encodeSettings(PREF_MODE, m.name)

    /** Reserve ("только Telegram") and Russian-exit ("Москва 🇷🇺") locations: they do not get around blocks. */
    fun isLastResort(name: String?): Boolean = AmeliaReserve.isReserve(name) || AmeliaReserve.isRussianExit(name)

    /**
     * The subscription is valid: no end date (expire 0 / unknown) or the end is still ahead.
     * Unknown info counts as valid: never send someone to "только Telegram" by mistake.
     */
    fun subscriptionActive(info: AmeliaSubInfo?, now: Long): Boolean =
        info?.expire?.takeIf { it > 0 }?.let { it * 1000 > now } ?: true

    /** Reserve and Moscow may be chosen automatically only after the subscription ended. */
    fun lastResortAllowed(info: AmeliaSubInfo?, now: Long): Boolean = !subscriptionActive(info, now)

    /**
     * No internet on the phone itself: Android has not validated the network AND neither ya.ru
     * nor google answer around the tunnel. Then nothing is judged or switched.
     */
    fun isOffline(validated: Boolean, directOk: Boolean): Boolean = !validated && !directOk

    /** Waits between checks while offline: 30 s, 1, 2, then every 5 min. */
    val OFFLINE_BACKOFF_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L)

    fun offlineWait(step: Int): Long = OFFLINE_BACKOFF_MS[step.coerceIn(0, OFFLINE_BACKOFF_MS.lastIndex)]

    /** Is the current location broken, given the failures seen so far? */
    fun broken(manual: Boolean, failures: Int, firstFailAt: Long, now: Long, noTraffic: Boolean): Boolean =
        if (manual) failures >= MANUAL_FAILURES && now - firstFailAt >= MANUAL_SPAN_MS
        else failures >= AUTO_FAILURES || (failures >= 1 && noTraffic)

    /** May the daemon move away from the current location on its own? */
    fun maySwitch(mode: Mode, manual: Boolean, respectUntil: Long, now: Long): Boolean = when (mode) {
        Mode.OFF -> false
        Mode.UNLESS_MANUAL -> !manual
        Mode.ALWAYS -> now >= respectUntil
    }

    /**
     * On a reserve or Moscow location while the subscription is valid (left from an older
     * version or a network blip): look for a working foreign one. Not when chosen by hand.
     */
    fun shouldLeaveLastResort(currentName: String?, manual: Boolean, subActive: Boolean): Boolean =
        isLastResort(currentName) && !manual && subActive
}

/**
 * Which location the person chose by hand, and the one the daemon left (for "Вернуться на …").
 * Stored in MMKV: the app sets it, the VPN daemon reads it. Kept by location name: a
 * subscription refresh gives every location a new guid, the choice must survive that.
 */
object AmeliaManual {

    data class State(
        /** Name of the location chosen by hand; null after an automatic choice. */
        val name: String? = null,
        val at: Long = 0L,
        /** The daemon does not leave it before this (the person came back to it). */
        val respectUntil: Long = 0L,
        /** Name of the location chosen by hand that the daemon left, and when. */
        val leftName: String? = null,
        val leftAt: Long = 0L,
    )

    private const val PREF = "amelia_manual"

    fun load(): State =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, State::class.java) } ?: State()

    private fun save(s: State) = MmkvManager.encodeSettings(PREF, JsonUtil.toJson(s))

    /** Pure: the person picked [name] at [now]. Coming back to the one we left is respected for 10 min. */
    fun chosen(s: State, name: String, now: Long): State {
        val cameBack = name == s.leftName && now - s.leftAt in 0..AmeliaPolicy.RETURN_WINDOW_MS
        return s.copy(name = name, at = now, respectUntil = if (cameBack) now + AmeliaPolicy.RESPECT_RETURN_MS else 0L)
    }

    /** Pure: the daemon moved from [from] (manual or not) to an automatic choice. */
    fun left(s: State, from: String?, now: Long): State =
        if (from != null && from == s.name) State(leftName = from, leftAt = now) else s.copy(name = null, respectUntil = 0L)

    private fun nameOf(guid: String?): String? = guid?.let { MmkvManager.decodeServerConfig(it)?.remarks }

    fun markChosen(guid: String, now: Long = System.currentTimeMillis()) {
        val name = nameOf(guid) ?: return
        save(chosen(load(), name, now))
    }

    /** "⚡ Подключить лучший" and other automatic picks: not a manual choice. */
    fun markAutomatic() = save(load().copy(name = null, respectUntil = 0L))

    fun markLeft(fromGuid: String?, now: Long = System.currentTimeMillis()) = save(left(load(), nameOf(fromGuid), now))

    fun isManual(guid: String?): Boolean = nameOf(guid)?.let { it == load().name } == true

    /** Guid of the location called [name] now (after a refresh it has a new one). */
    fun guidOf(name: String): String? =
        MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).firstOrNull { nameOf(it) == name }
}

/**
 * "Журнал" (1.4.0): the last [MAX] switches and checks in plain words, for the person and for
 * support. Separate from AmeliaEvents, which is the report's "events" field (switches only).
 */
object AmeliaJournal {
    const val MAX = 20
    private const val PREF = "amelia_journal"

    data class Entry(val ts: Long = 0L, val text: String = "")

    private class Log(val entries: List<Entry> = emptyList())

    fun list(): List<Entry> =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Log::class.java)?.entries } ?: emptyList()

    /** Pure: [e] added; the same text right after itself is not repeated, only its time moves. */
    fun append(list: List<Entry>, e: Entry): List<Entry> =
        (if (list.lastOrNull()?.text == e.text) list.dropLast(1) else list).plus(e).takeLast(MAX)

    fun add(text: String, now: Long = System.currentTimeMillis()) =
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson(Log(append(list(), Entry(now, text)))))
}
