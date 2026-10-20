package com.v2ray.ang.amelia

import android.content.Context
import com.google.gson.annotations.SerializedName
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
    private val lock = Any()

    fun load(): Store =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Store::class.java) } ?: Store()

    private fun save(s: Store) = MmkvManager.encodeSettings(PREF, JsonUtil.toJson(s))

    /** Stores one result and feeds the blocking map (AmeliaProbe). Any process. */
    fun put(context: Context, r: AmeliaCheck.Result, net: String = AmeliaHints.netKey(context)) {
        if (r.name.isEmpty()) return
        val e = Entry(r.name, r.host, r.port, r.ms, r.stage, System.currentTimeMillis(), net)
        synchronized(lock) { save(load().let { it.copy(entries = it.entries + (r.name to e)) }) }
        AmeliaProbe.recordPing(context, r.guid, r.ms, r.stage, r.error)
    }

    /** A result from the test service (by guid). */
    fun putPing(context: Context, guid: String, ms: Long, stage: String, error: String?) {
        val c = MmkvManager.decodeServerConfig(guid) ?: return
        if (c.subscriptionId != AmeliaConfig.SUBSCRIPTION_ID) return
        put(context, AmeliaCheck.Result(guid, c.remarks, c.server.orEmpty(), c.serverPort?.toIntOrNull(), ms, stage, error))
    }

    fun putFull(durationMs: Long) = synchronized(lock) {
        save(load().copy(lastFullAt = System.currentTimeMillis(), lastFullMs = durationMs))
    }

    /** Results younger than [FRESH_MS], by location name (the latest check of each). */
    fun fresh(store: Store, now: Long): Map<String, Entry> =
        store.entries.filterValues { now - it.ts in 0..FRESH_MS }
}

/** GET /app/hints: servers that do not work for this provider right now (asked around the tunnel). */
object AmeliaHints {

    const val PATH = "/app/hints"
    const val MAX_AGE_MS = 30L * 60 * 1000

    data class Bad(
        val host: String = "",
        val port: Int = 0,
        val bad: Int = 0,
        val of: Int = 0,
        val stage: String? = null,
    )

    data class Hints(
        val asn: Long? = null,
        @SerializedName("as") val asName: String? = null,
        val cc: String? = null,
        val bad: List<Bad> = emptyList(),
        val ts: Long? = null,
    )

    /** [net] = "type|operator" of the physical network the answer came on. */
    data class Cached(val hints: Hints = Hints(), val at: Long = 0L, val net: String = "")

    private const val PREF = "amelia_hints"

    fun parse(json: String?): Hints? =
        json?.let { runCatching { AmeliaProbe.wireGson.fromJson(it, Hints::class.java) }.getOrNull() }

    fun cached(): Cached? =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, Cached::class.java) }

    /** The cached answer if it is younger than 30 minutes and from the network we are on. */
    fun current(simNet: String, now: Long = System.currentTimeMillis()): Hints? =
        cached()?.takeIf { it.net == simNet && now - it.at in 0..MAX_AGE_MS }?.hints

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

    /** Blocking. Asks the server when the cached answer is stale or from another network; errors are ignored. */
    fun refreshIfStale(context: Context, force: Boolean = false) {
        val sim = AmeliaProbe.netKey(AmeliaNetwork.info(context))
        if (!force && current(sim) != null) return
        val body = AmeliaNetwork.getDirect(context, PATH) ?: return
        val h = parse(body) ?: return
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson(Cached(h, System.currentTimeMillis(), sim)))
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

    fun add(e: Event) = MmkvManager.encodeSettings(PREF, JsonUtil.toJson(Log(append(list(), e))))

    fun append(list: List<Event>, e: Event): List<Event> = (list + e).takeLast(MAX)
}

/**
 * Order in which automatic choice and failover try locations (pure, unit-tested):
 * the network's remembered best first (unless it was just seen failing), then working ones by
 * ping, then unchecked ones, then those the hints call bad for this provider (unless our own
 * check says they work), then those our check saw failing, reserve ("только Telegram") last.
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
    ): List<String> {
        val pool = locations.filter { it.guid !in exclude && (allowLastResort || !AmeliaPolicy.isLastResort(it.name)) }
        // Reserve ("только Telegram") and Russian-exit ("Москва 🇷🇺") locations do not get around
        // blocks: chosen automatically only when no foreign one works
        val (lastResort, regular) = pool.partition { AmeliaReserve.isReserve(it.name) || AmeliaReserve.isRussianExit(it.name) }
        fun group(l: Location): Int {
            val k = known[l.name]
            return when {
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
            compareBy<Location>({ group(it) }, { ms(it) }, { if (group(it) == 2 && it.name == preferred) 0 else 1 }),
        )
        // Russian exits before reserve ones; working ones first, by ping
        val tail = lastResort.sortedWith(
            compareBy<Location>({ if (AmeliaReserve.isReserve(it.name)) 1 else 0 }, { if (known[it.name]?.ok == true) 0 else 1 }, { ms(it) }),
        )
        return (ordered + tail).map { it.guid }
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
        val known = AmeliaChecks.fresh(AmeliaChecks.load(), now)
        return order(
            locations(), known, AmeliaHints.badHostPorts(AmeliaHints.current(sim)), AmeliaBest.get(net), exclude,
            allowLastResort = AmeliaPolicy.lastResortAllowed(AmeliaManager.getSubInfo(), now),
        )
    }
}
