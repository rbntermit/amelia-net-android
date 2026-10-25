package com.v2ray.ang.amelia

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/*
 * 1.5.9: fewer needless switches when mobile operators (ТСПУ) cut some servers or turn on
 * "white lists". Pure rules here (unit-tested), the daemon applies them. See docs/LOGIC.md.
 */

/**
 * A location left after a failure is not chosen automatically for [QUARANTINE_MS] on the same
 * network (AmeliaHints.netKey: provider's AS, mobile and Wi-Fi apart), unless nothing else
 * works (AmeliaQueue puts it after all others). Choosing it by hand lifts it.
 */
object AmeliaQuarantine {

    const val QUARANTINE_MS = 30L * 60 * 1000
    private const val MAX = 50
    private const val PREF = "amelia_quarantine"

    data class Item(val name: String = "", val net: String = "", val until: Long = 0L)

    private class Store(val items: List<Item> = emptyList())

    // ---- pure -------------------------------------------------------------------------------

    fun add(items: List<Item>, name: String, net: String, now: Long): List<Item> =
        (items.filter { it.until > now && !(it.name == name && it.net == net) } + Item(name, net, now + QUARANTINE_MS)).takeLast(MAX)

    fun active(items: List<Item>, net: String, now: Long): Set<String> =
        items.filter { it.net == net && it.until > now }.map { it.name }.toSet()

    /** Chosen by hand: free on every network. */
    fun release(items: List<Item>, name: String): List<Item> = items.filter { it.name != name }

    // ---- storage (any process) --------------------------------------------------------------

    private fun load(): List<Item> =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Store::class.java)?.items }.orEmpty()

    private fun save(items: List<Item>) = MmkvManager.encodeSettings(PREF, JsonUtil.toJson(Store(items)))

    fun put(name: String, net: String, now: Long = System.currentTimeMillis()) = synchronized(this) { save(add(load(), name, net, now)) }

    fun active(net: String, now: Long = System.currentTimeMillis()): Set<String> = active(load(), net, now)

    fun releaseName(name: String) = synchronized(this) {
        val items = load()
        if (items.any { it.name == name }) save(release(items, name))
    }
}

/**
 * At most [MAX] automatic switches in [WINDOW_MS]. Beyond that the daemon stays where it is,
 * checks again every [RECHECK_MS] and says once that the operator cuts part of the servers.
 * Counted from the switch log (AmeliaEvents, kept in MMKV, so it survives a restart).
 *
 * 1.6.0: the limit is against hopping between working locations, never a reason to sit on a dead
 * one. When the current location surely does not work ([currentDead]) and another one is known
 * to work right now ([verified]), the move is made over the limit ([Decision.OVER_LIMIT]); it is
 * logged with [OVER_LIMIT_NOTE] and does not count towards the limit ([times]).
 */
object AmeliaSwitchLimit {

    const val MAX = 4
    const val WINDOW_MS = 30L * 60 * 1000
    const val RECHECK_MS = 5L * 60 * 1000

    /** 1.6.0: the tunnel check failed this many times in a row: the current location surely does not work. */
    const val DEAD_FAILURES = 2
    /** 1.6.0: a location that passed a check this recently counts as working for a move over the limit. */
    const val VERIFIED_MS = 2L * 60 * 1000
    /** 1.6.0: added to the reason of a move over the limit (switch log and journal). */
    const val OVER_LIMIT_NOTE = "сверх лимита: текущая не работает"

    enum class Decision {
        /** Within the limit: the usual switch. */
        SWITCH,
        /** Limit reached, the current one is dead and a working one is known: move to it anyway. */
        OVER_LIMIT,
        /** Limit reached and nothing surely better: stay, check again in [RECHECK_MS]. */
        STAY,
    }

    fun allowed(switchTimes: List<Long>, now: Long): Boolean = switchTimes.count { now - it in 0 until WINDOW_MS } < MAX

    /**
     * Epoch millis of the logged automatic switches; moves over the limit (1.6.0) and the lines of
     * a new subscription download (1.6.1, AmeliaResubscribe) are not counted.
     */
    fun times(events: List<AmeliaEvents.Event>): List<Long> =
        events.filter { !isOverLimit(it.why) && !AmeliaResubscribe.isLogLine(it.why) }.map { it.ts * 1000 }

    fun isOverLimit(why: String): Boolean = why.contains(OVER_LIMIT_NOTE)

    /** "нет ответа 2 раза · сверх лимита: текущая не работает". */
    fun overLimitReason(why: String): String = "$why · $OVER_LIMIT_NOTE"

    /** [failures]: tunnel checks of the current location failed in a row. */
    fun currentDead(failures: Int): Boolean = failures >= DEAD_FAILURES

    /** Passed a check (ours: watch, quick pick, full check, "Проверить пинг") at most [VERIFIED_MS] ago. */
    fun verified(e: AmeliaChecks.Entry?, now: Long): Boolean = e != null && e.ok && now - e.ts in 0..VERIFIED_MS

    /**
     * Locations of [queue] (guids, queue order) that may be taken over the limit: [verified] by
     * their latest check in [known] (by name).
     */
    fun verifiedTargets(queue: List<String>, nameOf: (String) -> String?, known: Map<String, AmeliaChecks.Entry>, now: Long): List<String> =
        queue.filter { g -> nameOf(g)?.let { verified(known[it], now) } == true }

    /**
     * Locations from the hints' "good" list worth checking right now for a move over the limit:
     * not [exclude]d, not already [verified], foreign (reserve and Moscow are never "good"), most
     * passed checks first, at most [AmeliaQuickPick.QUICK_COUNT].
     */
    fun goodToCheck(
        locations: List<AmeliaQueue.Location>,
        good: Map<String, Int>,
        known: Map<String, AmeliaChecks.Entry>,
        exclude: Set<String>,
        now: Long,
    ): List<String> =
        locations.asSequence()
            .filter { (good[it.hostPort] ?: 0) > 0 && it.guid !in exclude && !verified(known[it.name], now) }
            .filter { !AmeliaReserve.isReserve(it.name) && !AmeliaReserve.isRussianExit(it.name) }
            .sortedByDescending { good[it.hostPort] ?: 0 }
            .take(AmeliaQuickPick.QUICK_COUNT).map { it.guid }.toList()

    /**
     * What to do once the current location is judged broken and switching is allowed by the mode.
     * [limitAllowed]: [allowed] for the log; [failures]: failed tunnel checks in a row;
     * [haveVerified]: some other location is [verified] (or from "good" and answered just now).
     */
    fun decide(limitAllowed: Boolean, failures: Int, haveVerified: Boolean): Decision = when {
        limitAllowed -> Decision.SWITCH
        currentDead(failures) && haveVerified -> Decision.OVER_LIMIT
        else -> Decision.STAY
    }
}

/**
 * "Белые списки": the operator lets only allowed Russian sites through. Checked around the
 * tunnel when most locations fail; then the daemon stops trying locations, shows a card and
 * checks again every [RECHECK_MS] and on every network change.
 */
object AmeliaWhitelist {

    /** Usually in the white lists. */
    val GROUP_A = listOf("https://ya.ru", "https://www.gosuslugi.ru", "https://vk.com")

    /** Not in the lists; the subscription server is added at check time. */
    val GROUP_B = listOf("https://www.google.com/generate_204", "https://1.1.1.1/cdn-cgi/trace")

    const val TIMEOUT_MS = 4_000L
    const val RECHECK_MS = 5L * 60 * 1000
    /** Share of locations failing in one check that calls for this check. */
    const val FAILED_SHARE = 0.8
    /** A check of fewer locations says too little. */
    const val MIN_CHECKED = 3
    /** Switch rounds in a row that found nothing working. */
    const val EMPTY_ROUNDS = 2

    enum class Verdict {
        /** Something from A, nothing from B. */
        WHITELIST,
        /** Nothing from A either: no internet (as before). */
        OFFLINE,
        /** B opens: servers are cut one by one, the usual rules apply. */
        NORMAL,
    }

    // ---- pure -------------------------------------------------------------------------------

    fun verdict(aOk: Boolean, bOk: Boolean): Verdict = when {
        !aOk -> Verdict.OFFLINE
        !bOk -> Verdict.WHITELIST
        else -> Verdict.NORMAL
    }

    /** 80% and more of the locations checked do not work. */
    fun tooManyFailed(failed: Int, total: Int): Boolean = total >= MIN_CHECKED && failed >= total * FAILED_SHARE

    /** Should the check run now? Only on mobile networks: the card asks to move to Wi-Fi. */
    fun shouldCheck(onMobile: Boolean, tooManyFailed: Boolean, emptyRounds: Int): Boolean =
        onMobile && (tooManyFailed || emptyRounds >= EMPTY_ROUNDS)

    /** In the mode: leave it when B opens or the phone is on Wi-Fi. */
    fun shouldExit(bOk: Boolean, onWifi: Boolean): Boolean = bOk || onWifi

    fun groupB(subscriptionBase: String?): List<String> = GROUP_B + listOfNotNull(subscriptionBase)

    // ---- state (MMKV: the daemon writes it, probe and report read it) ------------------------

    private const val PREF = "amelia_whitelist"

    data class State(val active: Boolean = false, val since: Long = 0L, val net: String = "")

    fun load(): State =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, State::class.java) } ?: State()

    fun isActive(): Boolean = load().active

    fun enter(net: String, now: Long = System.currentTimeMillis()) =
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson(State(true, now, net)))

    fun exit() {
        if (load().active) MmkvManager.encodeSettings(PREF, JsonUtil.toJson(State()))
    }

    // ---- requests around the tunnel -----------------------------------------------------------

    private val baseClient: OkHttpClient by lazy {
        AmeliaHttp.client.newBuilder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    /** Any HTTP answer within [TIMEOUT_MS] over the phone's own network counts as "opened". */
    private fun opens(client: OkHttpClient, url: String): Boolean = try {
        client.newCall(AmeliaHttp.get(url)).execute().use { true }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Amelia white lists: $url did not open: ${e.message}")
        false
    }

    /** Does at least one of [urls] open? All at the same time. */
    suspend fun anyOpens(context: Context, urls: List<String>): Boolean = coroutineScope {
        val client = AmeliaNetwork.directClient(context, baseClient)
        urls.map { u -> async(Dispatchers.IO) { opens(client, u) } }.awaitAll().any { it }
    }

    /** Base address of the subscription server (the one that last answered). */
    fun subscriptionBase(): String? = AmeliaManager.lastSubscriptionSource()?.first ?: AmeliaConfig.SUB_BASE_URLS.firstOrNull()
}

/**
 * 1.6.1: when an operator cuts the way in, the server moves the location to another port, and
 * the new port comes only with the subscription. So before "nothing works" (and before the white
 * list check) the daemon downloads the subscription again if it is older than [STALE_MS]: msk2,
 * then msk1, around the tunnel, [TIMEOUT_MS] in all, at most once in [MIN_INTERVAL_MS] per network.
 * Locations whose address or port changed are checked at once; their quarantine and old results
 * are dropped. Unchanged or not downloaded: everything goes on as before.
 */
object AmeliaResubscribe {

    const val STALE_MS = 10L * 60 * 1000
    const val MIN_INTERVAL_MS = 10L * 60 * 1000
    const val TIMEOUT_MS = 10_000L
    /** Lines of the switch log written by this check start with it; they are not switches ([AmeliaSwitchLimit.times]). */
    const val LOG_PREFIX = "подписка "
    private const val MAX_NETS = 20
    private const val PREF = "amelia_resubscribe_by_net"

    // ---- pure -------------------------------------------------------------------------------

    /** The subscription is older than [STALE_MS] and this network had no try in the last [MIN_INTERVAL_MS]. */
    fun due(lastUpdate: Long, lastTry: Long?, now: Long): Boolean =
        now - lastUpdate > STALE_MS && (lastTry == null || now - lastTry !in 0 until MIN_INTERVAL_MS)

    /**
     * Names of locations ("name" → "host:port", before and after) whose address or port changed,
     * new ones included, in the order of [after]. Removed ones are not here: nothing to check.
     */
    fun changed(before: Map<String, String>, after: List<Pair<String, String>>): List<String> =
        after.filter { (name, hostPort) -> before[name] != hostPort }.map { it.first }

    /** For the switch log and the journal: "подписка обновлена: изменилось 2 локации" / "подписка без изменений". */
    fun logLine(changed: Int): String =
        if (changed <= 0) "${LOG_PREFIX}без изменений"
        else "${LOG_PREFIX}обновлена: ${ruCount(changed)}"

    /** "изменилась 1 локация", "изменилось 2 локации", "изменилось 5 локаций". */
    internal fun ruCount(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "изменилась $n локация"
            mod10 in 2..4 && mod100 !in 12..14 -> "изменилось $n локации"
            else -> "изменилось $n локаций"
        }
    }

    fun isLogLine(why: String): Boolean = why.startsWith(LOG_PREFIX)

    /** The map of last tries after a try on [net] at [now]; at most [MAX_NETS] networks kept. */
    fun record(tries: Map<String, Long>, net: String, now: Long): Map<String, Long> =
        (tries - net).entries.sortedBy { it.value }.takeLast(MAX_NETS - 1).associate { it.key to it.value } + (net to now)

    // ---- storage (MMKV; the daemon writes it) -------------------------------------------------

    private class Store(val nets: Map<String, Long> = emptyMap())

    private fun load(): Map<String, Long> =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Store::class.java)?.nets }.orEmpty()

    fun lastTry(net: String): Long? = load()[net]

    fun tried(net: String, now: Long = System.currentTimeMillis()) = synchronized(this) {
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson(Store(record(load(), net, now))))
    }
}
