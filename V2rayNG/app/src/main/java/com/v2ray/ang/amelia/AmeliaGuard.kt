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
 */
object AmeliaSwitchLimit {

    const val MAX = 4
    const val WINDOW_MS = 30L * 60 * 1000
    const val RECHECK_MS = 5L * 60 * 1000

    fun allowed(switchTimes: List<Long>, now: Long): Boolean = switchTimes.count { now - it in 0 until WINDOW_MS } < MAX

    /** Epoch millis of the logged automatic switches. */
    fun times(events: List<AmeliaEvents.Event>): List<Long> = events.map { it.ts * 1000 }
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
