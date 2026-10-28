package com.v2ray.ang.amelia

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/**
 * What the app knows about its servers between checks (1.2.0), shared by all processes
 * through MMKV: the last check of each location, the server's hints about this provider,
 * the best location per network and the log of automatic switches.
 * Locations are keyed by name: import gives them new guids on every subscription refresh.
 */
object AmeliaChecks {

    /** Results older than this are not used to choose a location. */
    const val FRESH_MS = 15L * 60 * 1000

    data class Entry(
        val name: String = "",
        val host: String = "",
        val port: Int? = null,
        val ms: Long = -1,
        val stage: String = AmeliaPingStage.DATA,
        val ts: Long = 0L,
        /** Network key (AmeliaHints.netKey) the check was made on. */
        val net: String = "",
    ) {
        val ok: Boolean get() = ms >= 0
    }

    data class Store(
        val entries: Map<String, Entry> = emptyMap(),
        /** End of the last check of every location, and how long it took. */
        val lastFullAt: Long = 0L,
        val lastFullMs: Long = 0L,
    )

    private const val PREF = "amelia_checks"

    /** 1.7.0: the daemon, the check process and the app write here: one lock for all of them. */
    private fun <T> locked(block: () -> T): T = MmkvManager.withSettingsLock(block)

    fun load(): Store =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Store::class.java) } ?: Store()

    private fun save(s: Store) = MmkvManager.encodeSettings(PREF, JsonUtil.toJson(s))

    /** Stores one result and feeds the blocking map (AmeliaProbe). Any process. */
    fun put(context: Context, r: AmeliaCheck.Result, net: String = AmeliaHints.netKey(context)) {
        if (r.name.isEmpty()) return
        val e = Entry(r.name, r.host, r.port, r.ms, r.stage, System.currentTimeMillis(), net)
        locked { save(load().let { it.copy(entries = it.entries + (r.name to e)) }) }
        AmeliaProbe.recordPing(context, r.guid, r.ms, r.stage, r.error)
    }

    /** A result from the test service (by guid). */
    fun putPing(context: Context, guid: String, ms: Long, stage: String, error: String?) {
        val c = MmkvManager.decodeServerConfig(guid) ?: return
        if (c.subscriptionId != AmeliaConfig.SUBSCRIPTION_ID) return
        put(context, AmeliaCheck.Result(guid, c.remarks, c.server.orEmpty(), c.serverPort?.toIntOrNull(), ms, stage, error))
    }

    /** 1.6.1: results of [names] are about an address or port they no longer have (AmeliaResubscribe). */
    fun forget(names: Collection<String>) = locked {
        val s = load()
        if (names.any { it in s.entries }) save(s.copy(entries = s.entries - names.toSet()))
    }

    fun putFull(durationMs: Long) = locked {
        save(load().copy(lastFullAt = System.currentTimeMillis(), lastFullMs = durationMs))
    }

    /** Results younger than [FRESH_MS], by location name (the latest check of each), any network. */
    fun fresh(store: Store, now: Long): Map<String, Entry> =
        store.entries.filterValues { now - it.ts in 0..FRESH_MS }

    /**
     * 1.7.0: results younger than [FRESH_MS] made on [net] (AmeliaHints.netKey). A location checked
     * on Wi-Fi a minute ago says nothing about the mobile network: it is not "working" there.
     */
    fun fresh(store: Store, now: Long, net: String): Map<String, Entry> =
        store.entries.filterValues { it.net == net && now - it.ts in 0..FRESH_MS }
}

/**
 * GET /app/hints (asked around the tunnel): what does not work (bad) and, since 1.5.9, what
 * works (good) for other subscribers of this provider (AS), separately for mobile and Wi-Fi.
 */
object AmeliaHints {

    const val PATH = "/app/hints"
    const val MAX_AGE_MS = 30L * 60 * 1000
    /** 1.5.9: the whole request, all server addresses together; no answer → as before. */
    const val TIMEOUT_MS = 3_000L
    /** 1.5.9: before an automatic switch the hints are asked again if older than this. */
    const val BEFORE_SWITCH_MS = 2L * 60 * 1000
    /** 1.5.9: while the VPN is on, asked again this often. */
    const val WHILE_ON_MS = 15L * 60 * 1000
    /** Networks kept in the cache (1.5.9: one answer per network). */
    const val MAX_NETS = 10

    data class Bad(
        val host: String = "",
        val port: Int = 0,
        val bad: Int = 0,
        val of: Int = 0,
        val stage: String? = null,
    )

    /** 1.5.9: worked for [ok] of [of] recent checks from this provider. */
    data class Good(
        val host: String = "",
        val port: Int = 0,
        val ok: Int = 0,
        val of: Int = 0,
    )

    data class Hints(
        val asn: Long? = null,
        @SerializedName("as") val asName: String? = null,
        val cc: String? = null,
        /** 1.5.9: "mobile" / "wifi" as the server understood the request. */
        val net: String? = null,
        val bad: List<Bad> = emptyList(),
        val good: List<Good> = emptyList(),
        val ts: Long? = null,
    )

    /** [net] = "type|operator" of the physical network the answer came on. */
    data class Cached(val hints: Hints = Hints(), val at: Long = 0L, val net: String = "")

    /** Until 1.5.8: the single last answer. Still read when the per-network cache has nothing. */
    private const val PREF = "amelia_hints"
    /** 1.5.9: the last answer per network ("type|operator" → Cached). */
    private const val PREF_BY_NET = "amelia_hints_by_net"

    private class ByNet(val nets: Map<String, Cached> = emptyMap())

    fun parse(json: String?): Hints? =
        json?.let { runCatching { AmeliaProbe.wireGson.fromJson(it, Hints::class.java) }.getOrNull() }

    /** Pure: "/app/hints?net=mobile" for mobile and Wi-Fi, no parameter for anything else. */
    fun query(net: String): String = when (net) {
        AmeliaNetwork.NET_MOBILE, AmeliaNetwork.NET_WIFI -> "$PATH?net=$net"
        else -> PATH
    }

    /** Pure: the cache after an answer for [net]; the oldest networks beyond [MAX_NETS] are dropped. */
    fun store(nets: Map<String, Cached>, net: String, c: Cached): Map<String, Cached> =
        (nets - net).entries.sortedBy { it.value.at }.takeLast(MAX_NETS - 1).associate { it.key to it.value } + (net to c)

    private fun byNet(): Map<String, Cached> =
        MmkvManager.decodeSettingsString(PREF_BY_NET)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, ByNet::class.java)?.nets }.orEmpty()

    private fun legacy(): Cached? =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Cached::class.java) }

    /** The last answer on [simNet] (any age). */
    fun cached(simNet: String): Cached? = byNet()[simNet] ?: legacy()?.takeIf { it.net == simNet }

    /** The cached answer if it is younger than 30 minutes and from the network we are on. */
    fun current(simNet: String, now: Long = System.currentTimeMillis()): Hints? =
        cached(simNet)?.takeIf { now - it.at in 0..MAX_AGE_MS }?.hints

    /**
     * Network key: type + provider's AS from the hints, or type + SIM operator without them.
     * Never the Wi-Fi name (that needs the location permission).
     */
    fun netKey(context: Context): String {
        val info = AmeliaNetwork.info(context)
        val sim = AmeliaProbe.netKey(info)
        return key(info.net, sim, current(sim)?.asn)
    }

    internal fun key(net: String, simKey: String, asn: Long?): String = if (asn != null && asn > 0) "$net|AS$asn" else simKey

    /** "host:port" of the servers the hints call bad. */
    fun badHostPorts(h: Hints?): Set<String> = h?.bad.orEmpty().map { "${it.host}:${it.port}" }.toSet()

    /** 1.5.9: "host:port" → how many recent checks from this provider it passed. */
    fun goodHostPorts(h: Hints?): Map<String, Int> =
        h?.good.orEmpty().filter { it.ok > 0 }.associate { "${it.host}:${it.port}" to it.ok }

    /** Blocking. Asks the server when the cached answer is stale or from another network; errors are ignored. */
    fun refreshIfStale(context: Context, force: Boolean = false) = refreshIfOlder(context, MAX_AGE_MS, force)

    /**
     * Blocking. Asks again when this network's answer is older than [maxAgeMs]. No answer: the old one stays.
     * @return true when a new answer was stored (1.6.1: the list of locations redraws its marks)
     */
    fun refreshIfOlder(context: Context, maxAgeMs: Long, force: Boolean = false): Boolean {
        val info = AmeliaNetwork.info(context)
        val sim = AmeliaProbe.netKey(info)
        val now = System.currentTimeMillis()
        if (!force && cached(sim)?.let { now - it.at in 0 until maxAgeMs } == true) return false
        val body = AmeliaNetwork.getDirect(context, query(info.net), TIMEOUT_MS) ?: return false
        val h = parse(body) ?: return false
        synchronized(this) {
            MmkvManager.encodeSettings(PREF_BY_NET, JsonUtil.toJson(ByNet(store(byNet(), sim, Cached(h, now, sim)))))
        }
        return true
    }

    // ---- 1.6.1: marks "у вашего оператора" in the list of locations (pure, unit-tested) -----------

    /** Our own check on this network at most this old outweighs the hint. */
    const val OWN_CHECK_MS = 10L * 60 * 1000

    enum class Mark {
        /** "✓ работает у вашего оператора": in good. */
        GOOD,
        /** "⚠ у вашего оператора режут": in bad. */
        BAD,
    }

    /** The hints say something about this provider: an answer with a known AS. */
    fun usable(h: Hints?): Boolean = h != null && (h.asn ?: 0L) > 0L

    /**
     * Mark of one location from the hints of the current network. [own]: our latest check of it;
     * it counts only when made on [net] (AmeliaHints.netKey) at most [OWN_CHECK_MS] ago: a working
     * one never gets [Mark.BAD], a failing one never [Mark.GOOD]. No usable hints: no mark.
     */
    fun mark(hostPort: String, h: Hints?, own: AmeliaChecks.Entry?, net: String, now: Long): Mark? {
        if (!usable(h)) return null
        val ownFresh = own?.takeIf { it.net == net && now - it.ts in 0..OWN_CHECK_MS }
        val good = (goodHostPorts(h)[hostPort] ?: 0) > 0
        val bad = hostPort in badHostPorts(h)
        return when {
            good && ownFresh?.ok != false -> Mark.GOOD
            bad && ownFresh?.ok != true -> Mark.BAD
            else -> null
        }
    }

    /** Words in the AS name that say nothing to a person (legal forms, "AS"). */
    private val TECH_WORDS = setOf(
        "as", "pjsc", "ojsc", "cjsc", "jsc", "llc", "ltd", "inc", "ooo", "pao", "zao", "oao", "ao", "gmbh", "plc", "co",
        "ооо", "пао", "оао", "зао", "ао",
    )

    /**
     * "MTS-AS" → "MTS", "PJSC MegaFon" → "MegaFon", "AS12389 Rostelecom" → "Rostelecom",
     * "MEGAFON-AS" → "Megafon". Null when nothing readable is left (the line says "вашего оператора").
     */
    fun operatorName(asName: String?): String? {
        val asNumber = Regex("(?i)as\\d+")
        // "MEGAFON" reads better as "Megafon"; short ones ("MTS", "T2") stay as they are
        fun readable(p: String) = if (p.length > 3 && p.all { !it.isLetter() || it.isUpperCase() }) p.lowercase().replaceFirstChar(Char::uppercaseChar) else p
        val words = asName.orEmpty()
            .replace(Regex("[\"«»“”,.()]"), " ")
            .split(Regex("\\s+"))
            .map { w ->
                w.split('-', '_')
                    .filter { p -> p.any(Char::isLetter) && p.lowercase() !in TECH_WORDS && !asNumber.matches(p) }
                    .joinToString("-") { readable(it) }
            }
            .filter { it.isNotBlank() }
        return words.joinToString(" ").trim().ifEmpty { null }
    }
}

/** Best location per network, so returning to a network starts with what worked there. */
object AmeliaBest {
    private const val PREF = "amelia_best_by_net"

    private fun load(): Map<String, String> =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JsonUtil.parseString(it)?.entrySet()?.associate { e -> e.key to e.value.asString } }.getOrNull() }
            .orEmpty()

    fun get(net: String): String? = load()[net]

    fun remember(net: String, name: String) {
        val m = load()
        if (m[net] == name) return
        // a few dozen networks at most; keep the map small
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson((m - net).entries.toList().takeLast(30).associate { it.key to it.value } + (net to name)))
    }
}

/** The last [MAX] automatic switches, sent with the problem report as "events". */
object AmeliaEvents {
    const val MAX = 20
    private const val PREF = "amelia_switch_events"

    data class Event(val ts: Long = 0L, val from: String = "", val to: String = "", val why: String = "")

    private class Log(val events: List<Event> = emptyList())

    fun list(): List<Event> =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Log::class.java)?.events } ?: emptyList()

    /**
     * 1.7.0: under the settings lock shared by all processes: a lost line was a switch the limit
     * did not count (AmeliaSwitchLimit).
     */
    fun add(e: Event) = MmkvManager.withSettingsLock {
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson(Log(append(list(), e))))
    }

    fun append(list: List<Event>, e: Event): List<Event> = (list + e).takeLast(MAX)
}

/**
 * Order in which automatic choice tries locations ([order], pure, unit-tested; 1.7.3: a switch
 * after a failure goes down the list instead, [failoverPlan]):
 * 1.5.9: first those the hints call good for this provider (most passed checks first, then our
 * own ping), unless our own check saw them failing; then working ones by ping, then unchecked
 * ones (the network's remembered best first), then those the hints call bad (unless our own check
 * says they work), then those our check saw failing. Locations in quarantine (left after a
 * failure in this network, 1.5.9) come after all of those; reserve ("только Telegram") last.
 */
object AmeliaQueue {

    data class Location(val guid: String, val name: String, val hostPort: String)

    fun order(
        locations: List<Location>,
        known: Map<String, AmeliaChecks.Entry>,
        bad: Set<String>,
        preferred: String?,
        exclude: Set<String> = emptySet(),
        /** 1.4.0: reserve and Moscow only after the subscription ended (AmeliaPolicy). */
        allowLastResort: Boolean = true,
        /** 1.5.9: "host:port" → checks passed from this provider (AmeliaHints.goodHostPorts). */
        good: Map<String, Int> = emptyMap(),
        /** 1.5.9: names in quarantine on this network (AmeliaQuarantine). */
        quarantined: Set<String> = emptySet(),
    ): List<String> {
        val pool = locations.filter { it.guid !in exclude && (allowLastResort || !AmeliaPolicy.isLastResort(it.name)) }
        // Reserve ("только Telegram") and Russian-exit ("Москва 🇷🇺") locations do not get around
        // blocks: chosen automatically only when no foreign one works
        val (lastResort, regular) = pool.partition { AmeliaReserve.isReserve(it.name) || AmeliaReserve.isRussianExit(it.name) }
        fun group(l: Location): Int {
            val k = known[l.name]
            return when {
                // our own failed check here outweighs what others see
                (good[l.hostPort] ?: 0) > 0 && (k == null || k.ok) -> 0
                k != null && k.ok -> 1
                k != null -> 4
                l.hostPort in bad -> 3
                else -> 2
            }
        }
        // Working ones strictly by the last ping. The network's remembered best only goes first among
        // the unchecked ones (1.2.1: before, it jumped ahead of faster working locations).
        fun ms(l: Location) = known[l.name]?.ms?.takeIf { it >= 0 } ?: Long.MAX_VALUE
        val ordered = regular.sortedWith(
            compareBy<Location>(
                { group(it) },
                { if (group(it) == 0) -(good[it.hostPort] ?: 0) else 0 },
                { ms(it) },
                { if (group(it) == 2 && it.name == preferred) 0 else 1 },
            ),
        )
        // 1.5.9: quarantined ones only when nothing else is left to try
        val (held, free) = ordered.partition { it.name in quarantined }
        // Russian exits before reserve ones; working ones first, by ping
        val tail = lastResort.sortedWith(
            compareBy<Location>({ if (AmeliaReserve.isReserve(it.name)) 1 else 0 }, { if (known[it.name]?.ok == true) 0 else 1 }, { ms(it) }),
        )
        return (free + held + tail).map { it.guid }
    }

    // ---- 1.7.3: failover goes down the list (pure, unit-tested) ----------------------------------

    /** Why a location stands where it does in the failover order, or why it is not in it. */
    enum class Why {
        /** Next in the subscription's own order. */
        LIST,
        /** Moved to the end: the hints call it cut for this provider (our own fresh check does not say it works). */
        HINT_BAD,
        /** Moved to the end: our own check on this network failed lately, or it was left after a failure (quarantine). */
        PAST_FAIL,
        /** At the very end: reserve or Russian exit, allowed only after the subscription ended. */
        LAST_RESORT,
        /** Not tried: reserve ("только Telegram") while the subscription is valid. */
        SKIP_RESERVE,
        /** Not tried: Russian exit ("Москва 🇷🇺") while the subscription is valid. */
        SKIP_RUSSIAN,
    }

    data class Step(val guid: String, val name: String, val why: Why)

    /** [steps]: what is tried, in this order; [skipped]: what is not tried at all, and why. */
    data class Plan(val steps: List<Step>, val skipped: List<Step>)

    /**
     * Order of an automatic switch after [current] did not answer: the subscription's own order
     * ([locations] as the server sent it), starting right after [current] and wrapping around.
     * Nothing jumps ahead. Moved to the end, keeping their order: those the hints call cut here
     * ([bad] "host:port"; our own fresh OK check in [known] outweighs it), then those that failed
     * lately ([known] failed, or [quarantined]). Reserve ("только Telegram") and Russian exits only
     * when [allowLastResort] (subscription ended), last. [tried]: names that did not answer during
     * this attempt, left out. Before 1.7.3 the queue of [order] was used: working ones by ping first,
     * so a fast location far down the list jumped ahead of the next one.
     */
    fun failoverPlan(
        locations: List<Location>,
        current: String?,
        tried: Set<String>,
        bad: Set<String>,
        known: Map<String, AmeliaChecks.Entry>,
        quarantined: Set<String>,
        allowLastResort: Boolean,
    ): Plan {
        val at = locations.indexOfFirst { it.name == current }
        val ring = if (at < 0) locations else locations.drop(at + 1) + locations.take(at + 1)
        val steps = mutableListOf<Step>()
        val skipped = mutableListOf<Step>()
        for (l in ring) {
            if (l.name in tried) continue
            val k = known[l.name]
            val why = when {
                AmeliaReserve.isReserve(l.name) -> if (allowLastResort) Why.LAST_RESORT else Why.SKIP_RESERVE
                AmeliaReserve.isRussianExit(l.name) -> if (allowLastResort) Why.LAST_RESORT else Why.SKIP_RUSSIAN
                l.name in quarantined || (k != null && !k.ok) -> Why.PAST_FAIL
                l.hostPort in bad && k?.ok != true -> Why.HINT_BAD
                else -> Why.LIST
            }
            val step = Step(l.guid, l.name, why)
            if (why == Why.SKIP_RESERVE || why == Why.SKIP_RUSSIAN) skipped += step else steps += step
        }
        // stable: inside each group the list order stays; Russian exits before reserve ones (as in [order])
        val rank = { s: Step ->
            when (s.why) {
                Why.LIST -> 0
                Why.HINT_BAD -> 1
                Why.PAST_FAIL -> 2
                else -> if (AmeliaReserve.isReserve(s.name)) 4 else 3
            }
        }
        return Plan(steps.sortedBy(rank), skipped)
    }

    /**
     * The first [AmeliaQuickPick.QUICK_COUNT] of [plan] that have no fresh result on this network
     * ([fresh]): checked at once before the switch, so a dead one is passed over without waiting
     * for the tunnel (the order itself does not change).
     */
    fun toCheckFirst(plan: Plan, fresh: Map<String, AmeliaChecks.Entry>): List<String> =
        plan.steps.take(AmeliaQuickPick.QUICK_COUNT).filter { fresh[it.name] == null }.map { it.guid }

    /** The failover plan now, from everything stored; blocking (MMKV, network info). */
    fun failoverNow(context: Context, current: String?, tried: Set<String>): Plan {
        val net = AmeliaHints.netKey(context)
        val sim = AmeliaProbe.netKey(AmeliaNetwork.info(context))
        val now = System.currentTimeMillis()
        return failoverPlan(
            locations(), current, tried,
            bad = AmeliaHints.badHostPorts(AmeliaHints.current(sim)),
            // only this network's own results (1.7.0)
            known = AmeliaChecks.fresh(AmeliaChecks.load(), now, net),
            quarantined = AmeliaQuarantine.active(net, now),
            allowLastResort = AmeliaPolicy.lastResortAllowed(AmeliaManager.getSubInfo(), now),
        )
    }

    private fun names(steps: List<Step>): String = steps.joinToString(", ") { "«${it.name}»" }

    /**
     * 1.7.3, for the journal: why the list is in this order. The order itself is the
     * subscription's; the app only moves the groups named here to the end (or leaves them out).
     */
    fun orderLine(plan: Plan, current: String?, overLimit: Boolean, str: AmeliaJournal.Strings): String {
        val parts = mutableListOf(
            if (current.isNullOrEmpty()) str.get(R.string.amelia_journal_order_top)
            else str.get(R.string.amelia_journal_order_after, current),
        )
        fun add(why: Why, id: Int, from: List<Step> = plan.steps) {
            val list = from.filter { it.why == why }
            if (list.isNotEmpty()) parts += str.get(id, names(list))
        }
        add(Why.HINT_BAD, R.string.amelia_journal_order_hints)
        add(Why.PAST_FAIL, R.string.amelia_journal_order_past)
        add(Why.LAST_RESORT, R.string.amelia_journal_order_last_resort)
        add(Why.SKIP_RESERVE, R.string.amelia_journal_order_skip_reserve, plan.skipped)
        add(Why.SKIP_RUSSIAN, R.string.amelia_journal_order_skip_russian, plan.skipped)
        if (overLimit) parts += str.get(R.string.amelia_journal_order_over_limit)
        return parts.joinToString("; ")
    }

    /**
     * 1.7.3, for the journal: "«Казахстан 2» не ответил — пробую «Казахстан 1» (следующий по
     * списку)"; [skippedNow]: names a check during this attempt saw failing, passed over.
     */
    fun tryLine(failed: String, next: Step, skippedNow: List<String>, str: AmeliaJournal.Strings): String {
        val why = when (next.why) {
            Why.HINT_BAD -> R.string.amelia_journal_why_hints
            Why.PAST_FAIL -> R.string.amelia_journal_why_past
            Why.LAST_RESORT -> R.string.amelia_journal_why_last_resort
            else -> R.string.amelia_journal_why_list
        }
        val line = str.get(R.string.amelia_journal_try, failed, next.name, str.get(why))
        return if (skippedNow.isEmpty()) line
        else line + "; " + str.get(R.string.amelia_journal_skipped_now, skippedNow.joinToString(", ") { "«$it»" })
    }

    fun locations(): List<Location> =
        MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { Location(guid, it.remarks, "${it.server}:${it.serverPort}") }
        }

    /** The queue now, from everything stored; blocking (MMKV, network info). */
    fun current(context: Context, exclude: Set<String> = emptySet()): List<String> {
        val net = AmeliaHints.netKey(context)
        val sim = AmeliaProbe.netKey(AmeliaNetwork.info(context))
        val now = System.currentTimeMillis()
        // 1.7.0: only this network's results order the queue
        val known = AmeliaChecks.fresh(AmeliaChecks.load(), now, net)
        val hints = AmeliaHints.current(sim)
        return order(
            locations(), known, AmeliaHints.badHostPorts(hints), AmeliaBest.get(net), exclude,
            allowLastResort = AmeliaPolicy.lastResortAllowed(AmeliaManager.getSubInfo(), now),
            good = AmeliaHints.goodHostPorts(hints),
            quarantined = AmeliaQuarantine.active(net, now),
        )
    }
}
