package com.v2ray.ang.amelia

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The one server check used by "Проверить пинг", "Не работает — сообщить", failover and the
 * background checks (1.2.0): a real request through each server, [PARALLEL] at a time.
 *
 * Per server: TCP connect at most [CONNECT_TIMEOUT_MS] (TCP protocols), then the whole test
 * request at most [REQUEST_TIMEOUT_MS] (hysteria2: the same). Reserve locations ("только
 * Telegram") are asked for api.telegram.org, see AmeliaReserve.
 *
 * libv2ray's measureOutboundDelay has a fixed 12 s timeout and two attempts and cannot be
 * cancelled, so it runs on [nativePool] and is simply not waited for after the timeout; the
 * abandoned call ends on its own within ~25 s.
 */
object AmeliaCheck {

    /** How many servers are checked at the same time. */
    const val PARALLEL = 6
    const val CONNECT_TIMEOUT_MS = 3_000
    const val REQUEST_TIMEOUT_MS = 5_000L
    const val HYSTERIA2_TIMEOUT_MS = 5_000L
    /** Shown in reports when the app stops waiting (1.2.1: was the core's own text). */
    const val TIMEOUT_ERROR = "нет ответа за 5 с"

    data class Result(
        val guid: String,
        val name: String,
        val host: String,
        val port: Int?,
        /** Delay in ms, -1 = does not work. */
        val ms: Long,
        val stage: String,
        val error: String?,
    ) {
        val ok: Boolean get() = ms >= 0
    }

    /**
     * 1.7.0: at most this many test cores run in one process at a time. A thread stays busy until
     * its core has finished (also when nobody waits for it any more), so abandoned checks count
     * too. Until 1.6.1 the pool had no limit: with 20–30 servers hanging on TLS (a typical block),
     * 25–30 cores lived at once and a cheap phone could kill the process, VPN included.
     */
    const val MAX_CORES = 8
    /** 1.7.0: how long a check may wait for a free slot before it counts as "no answer". */
    const val SLOT_WAIT_MS = 15_000L

    private val nativePool = ThreadPoolExecutor(
        MAX_CORES, MAX_CORES, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "amelia-check").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

    /**
     * 1.7.0: checks running right now, by location (name, address, port). A second check of the
     * same location joins the running one instead of starting another core.
     */
    private val inFlight = ConcurrentHashMap<String, Running>()

    private class Running(val started: CompletableFuture<Long>, val result: Future<Pair<Long, String?>>)

    /** Pure: a location's key for [inFlight] (a new port after a subscription download is another check). */
    fun inFlightKey(name: String, host: String, port: Int?): String = "$name|$host:${port ?: 0}"

    /** Pure: what is left of the request timeout once the core actually started at [startedAt]. */
    fun timeLeft(timeoutMs: Long, startedAt: Long, now: Long): Long = (timeoutMs - (now - startedAt)).coerceAtLeast(0L)

    private fun measure(key: String, config: String, url: String): Running {
        while (true) {
            val existing = inFlight[key]
            if (existing != null && !existing.result.isDone) return existing
            val started = CompletableFuture<Long>()
            var self: Running? = null
            val task = FutureTask {
                started.complete(System.nanoTime())
                try {
                    CoreNativeManager.measureOutboundDelayDetailed(config, url)
                } finally {
                    self?.let { inFlight.remove(key, it) }
                }
            }
            val running = Running(started, task)
            self = running
            val stored = if (existing == null) inFlight.putIfAbsent(key, running) == null else inFlight.replace(key, existing, running)
            if (stored) {
                nativePool.execute(task)
                return running
            }
            // another thread stored its check first: join that one
        }
    }

    /** Blocking. Checks one location, at most ~[CONNECT_TIMEOUT_MS] + [REQUEST_TIMEOUT_MS]. */
    fun checkOne(context: Context, guid: String): Result {
        val config = MmkvManager.decodeServerConfig(guid)
            ?: return Result(guid, "", "", null, -1, AmeliaPingStage.CONNECT, "no config")
        val name = config.remarks
        val host = config.server.orEmpty()
        val port = config.serverPort?.toIntOrNull()
        fun result(ms: Long, stage: String, error: String?) =
            Result(guid, name, host, port, ms, stage, error?.take(AmeliaPingStage.MAX_ERROR))

        val hy2 = config.configType == EConfigType.HYSTERIA2
        val tcp = !config.configType.isComplexType() && !hy2 && config.configType != EConfigType.WIREGUARD &&
            config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true &&
            host.isNotEmpty() && port != null
        var tcpOk: Boolean? = null
        if (tcp) {
            if (SpeedtestManager.socketConnectTime(host, port!!, CONNECT_TIMEOUT_MS) < 0) {
                return result(-1, AmeliaPingStage.CONNECT, AmeliaPingStage.TCP_FAILED)
            }
            tcpOk = true
        }
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) return result(-1, AmeliaPingStage.CONNECT, "no config")

        val reserve = AmeliaReserve.isReserve(name)
        val url = AmeliaReserve.testUrl(name, SettingsManager.getDelayTestUrl())
        val timeout = if (hy2) HYSTERIA2_TIMEOUT_MS else REQUEST_TIMEOUT_MS
        val running = measure(inFlightKey(name, host, port), configResult.content, url)
        // 1.7.0: the timeout counts from the moment the core started, not from the queue
        val startedNs = try {
            running.started.get(SLOT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
        val (raw, rawError) = if (startedNs == null) {
            LogUtil.w(AppConfig.TAG, "Amelia check: no free slot for a test core in $SLOT_WAIT_MS ms")
            -1L to TIMEOUT_ERROR
        } else {
            try {
                running.result.get(timeLeft(timeout * 1_000_000, startedNs, System.nanoTime()) / 1_000_000, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                -1L to TIMEOUT_ERROR
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia check: measure failed for a location", e)
                -1L to (e.message ?: e.javaClass.simpleName)
            }
        }
        val elapsedMs = startedNs?.let { (System.nanoTime() - it) / 1_000_000 } ?: SLOT_WAIT_MS
        val (ms, error) = AmeliaReserve.interpret(reserve, raw, rawError, elapsedMs)
        return result(ms, AmeliaPingStage.classify(ms, tcpOk, error), error)
    }

    /**
     * Checks every location in [guids], [PARALLEL] at a time; [onResult] is called as soon as
     * each one is known. Returns how long the whole check took, ms.
     */
    suspend fun checkAll(
        context: Context,
        guids: List<String>,
        check: (String) -> Result = { checkOne(context, it) },
        onResult: (Result) -> Unit,
    ): Long {
        val started = System.currentTimeMillis()
        val permits = Semaphore(PARALLEL)
        coroutineScope {
            guids.map { guid ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        val r = withContext(Dispatchers.IO) { check(guid) }
                        onResult(r)
                    }
                }
            }.awaitAll()
        }
        return System.currentTimeMillis() - started
    }
}

/**
 * 1.2.1: when the known results are stale, failover does not take the first location of the
 * list: the few best by past ping are checked in parallel for [QUICK_DEADLINE_MS] and the
 * fastest that answered wins.
 */
object AmeliaQuickPick {

    const val QUICK_DEADLINE_MS = 3_000L
    const val QUICK_COUNT = 4

    /** Results that finish after the deadline still reach the store. */
    private val lateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Foreign (not reserve, not Russian-exit) locations other than [exclude], best past ping
     * first (any age; never-checked ones after), at most [QUICK_COUNT].
     */
    fun candidates(
        locations: List<AmeliaQueue.Location>,
        last: Map<String, AmeliaChecks.Entry>,
        exclude: Set<String>,
        /** 1.5.9: what works for this provider (AmeliaHints.goodHostPorts) is checked first. */
        good: Map<String, Int> = emptyMap(),
    ): List<String> =
        locations.asSequence()
            .filter { it.guid !in exclude && !AmeliaReserve.isReserve(it.name) && !AmeliaReserve.isRussianExit(it.name) }
            .sortedWith(
                compareBy(
                    { -(good[it.hostPort] ?: 0) },
                    { if (last[it.name]?.ok == true) 0 else if (last[it.name] == null) 1 else 2 },
                    { last[it.name]?.ms?.takeIf { ms -> ms >= 0 } ?: Long.MAX_VALUE },
                ),
            )
            .take(QUICK_COUNT).map { it.guid }.toList()

    /** Checks [guids] at the same time; those that answered within [deadlineMs], fastest first. */
    suspend fun quickest(
        guids: List<String>,
        deadlineMs: Long = QUICK_DEADLINE_MS,
        check: (String) -> AmeliaCheck.Result,
        onResult: (AmeliaCheck.Result) -> Unit,
    ): List<AmeliaCheck.Result> {
        val channel = Channel<AmeliaCheck.Result>(Channel.UNLIMITED)
        guids.forEach { g ->
            lateScope.launch {
                val r = check(g)
                onResult(r)
                channel.trySend(r)
            }
        }
        val answered = mutableListOf<AmeliaCheck.Result>()
        withTimeoutOrNull(deadlineMs) {
            repeat(guids.size) {
                val r = channel.receive()
                if (r.ok) answered += r
            }
        }
        return answered.sortedBy { it.ms }
    }
}
