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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
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
    const val TIMEOUT_ERROR = "i/o timeout: no answer in 5 s"

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

    private val nativePool = Executors.newCachedThreadPool { r ->
        Thread(r, "amelia-check").apply { isDaemon = true }
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
        val started = System.nanoTime()
        val future = nativePool.submit<Pair<Long, String?>> {
            CoreNativeManager.measureOutboundDelayDetailed(configResult.content, url)
        }
        val (raw, rawError) = try {
            future.get(timeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            -1L to TIMEOUT_ERROR
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia check: measure failed for a location", e)
            -1L to (e.message ?: e.javaClass.simpleName)
        }
        val (ms, error) = AmeliaReserve.interpret(reserve, raw, rawError, (System.nanoTime() - started) / 1_000_000)
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
