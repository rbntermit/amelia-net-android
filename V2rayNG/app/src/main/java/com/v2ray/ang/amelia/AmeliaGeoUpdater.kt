package com.v2ray.ang.amelia

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Our own Russia-only geo bases (geoip.dat / geosite.dat, a few hundred KB), served by the
 * subscription server at /geo/<file> and rebuilt daily. Downloaded on first start and once a day;
 * the geosite:category-ru / geoip:ru rules are switched on only once both files are present
 * (see [AmeliaRouting.geoFilesReady]).
 */
object AmeliaGeoUpdater {

    val FILES = listOf(AppConfig.GEOIP_DAT, AppConfig.GEOSITE_DAT)
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_SIZE = 64L * 1024 * 1024
    private const val PREF_GEO_UPDATED = "amelia_geo_updated"
    private const val WORK_NAME = "amelia_geo_update"

    fun lastUpdated(): Long = MmkvManager.decodeSettingsString(PREF_GEO_UPDATED)?.toLongOrNull() ?: 0L

    /** Pure decision, unit-tested. */
    internal fun isDue(filesReady: Boolean, lastUpdated: Long, now: Long): Boolean =
        !filesReady || now - lastUpdated >= MAX_AGE_MS || lastUpdated > now

    fun isDue(context: Context): Boolean =
        isDue(AmeliaRouting.geoFilesReady(context), lastUpdated(), System.currentTimeMillis())

    /**
     * Downloads both files (blocking, call off the main thread) and re-applies routing.
     * A failed download keeps the previous file.
     * @return true when the geo rules were just switched on, so a running VPN should restart.
     */
    fun update(context: Context): Boolean {
        val wasReady = AmeliaRouting.geoFilesReady(context)
        val dir = File(Utils.userAssetPath(context))
        if (!dir.isDirectory) return false

        val allOk = FILES.all { name -> downloadOne(dir, name) }
        if (allOk) {
            MmkvManager.encodeSettings(PREF_GEO_UPDATED, System.currentTimeMillis().toString())
        }
        AmeliaRouting.apply(context)
        return !wasReady && AmeliaRouting.geoFilesReady(context)
    }

    /**
     * Through the tunnel first when the VPN is on, then directly; last good address first.
     * 1.7.2: into a temporary file; it replaces the working one only when it is complete
     * (Content-Length) and [check] passes, so a failed download never touches the working file.
     */
    private fun downloadOne(dir: File, name: String): Boolean {
        for (a in AmeliaHttp.currentPlan()) {
            // own name per writer: the start-time repair in another process may write next to it
            val tmp = File.createTempFile("$name.", ".download", dir)
            try {
                val ok = AmeliaHttp.clientFor(a.route)
                    .newCall(AmeliaHttp.get(a.base + AmeliaConfig.GEO_PATH + name)).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            LogUtil.w(AppConfig.TAG, "Amelia: geo $name HTTP ${resp.code} from ${a.base} (${a.route})")
                            false
                        } else {
                            val expected = resp.body.contentLength()
                            resp.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            sizeMatches(expected, tmp.length()).also {
                                if (!it) LogUtil.w(AppConfig.TAG, "Amelia: geo $name cut short: ${tmp.length()} of $expected bytes from ${a.base}")
                            }
                        }
                    }
                if (ok && install(tmp, File(dir, name)) { check(it, name) }) {
                    AmeliaHttp.rememberBase(a.base)
                    return true
                }
                if (ok) LogUtil.w(AppConfig.TAG, "Amelia: geo $name from ${a.base} is damaged, the working file stays")
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia: geo $name download failed from ${a.base} (${a.route})", e)
            } finally {
                tmp.delete()
            }
        }
        return false
    }

    // ---- 1.7.2: damaged geo bases ----------------------------------------------------------
    //
    // A geoip.dat cut short ("invalid body length", Xiaomi, Android 16, 1.7.1) stopped Xray from
    // starting at all. Files are checked before they replace the working ones, at app start and
    // daily; a core start failing on them restores the bases built into the APK and tries once more.

    /** The list each base must have: the routing uses geoip:ru and geosite:category-ru. */
    fun requiredCode(name: String): String = if (name == AppConfig.GEOSITE_DAT) "CATEGORY-RU" else "RU"

    /** Pure: a download is complete when the server said nothing about its size or it matches. */
    fun sizeMatches(contentLength: Long, actual: Long): Boolean = contentLength < 0 || contentLength == actual

    /** The result of [scan]: the lists found, or why the file cannot be read. */
    data class Scan(val codes: Set<String>, val error: String? = null) {
        val ok: Boolean get() = error == null
    }

    /**
     * Pure, streaming: walks a GeoIPList / GeoSiteList (protobuf: repeated field 1, each entry
     * starting with its country_code) without reading the entry bodies, so the 16 MB base built
     * into the APK takes milliseconds. Exactly [size] bytes must be entries — a file cut short
     * ends inside an entry, which is what Xray reports as "invalid body length".
     */
    fun scan(input: InputStream, size: Long): Scan {
        val codes = mutableSetOf<String>()
        var pos = 0L
        fun byte(): Int = input.read().also { if (it >= 0) pos++ }
        fun varint(): Long? {
            var result = 0L
            for (shift in 0 until 64 step 7) {
                val b = byte()
                if (b < 0) return null
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
            }
            return null
        }
        fun skip(n: Long): Boolean {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s > 0) {
                    left -= s; pos += s
                } else if (byte() < 0) {
                    return false
                } else {
                    left--
                }
            }
            return true
        }
        if (size <= 0) return Scan(codes, "empty")
        while (pos < size) {
            if (byte() != 0x0A) return Scan(codes, "not a geo base at $pos")
            val len = varint() ?: return Scan(codes, "cut short at $pos")
            val start = pos
            if (len < 0 || start + len > size) return Scan(codes, "invalid body length $len at $start")
            // country_code (field 1, string) comes first in every entry Xray's tools write
            if (len >= 2 && byte() == 0x0A) {
                val n = varint() ?: return Scan(codes, "cut short at $pos")
                if (n in 1..64 && pos + n <= start + len) {
                    val name = ByteArray(n.toInt())
                    for (i in name.indices) name[i] = byte().takeIf { it >= 0 }?.toByte() ?: return Scan(codes, "cut short at $pos")
                    codes += String(name, Charsets.US_ASCII).uppercase()
                }
            }
            val rest = start + len - pos
            if (rest < 0 || !skip(rest)) return Scan(codes, "cut short at $pos")
        }
        if (input.read() >= 0) return Scan(codes, "longer than $size bytes")
        return Scan(codes)
    }

    /**
     * Pure (no logging): null when [file] is a whole geo base of a sane size with the list the
     * routing uses ([requiredCode]), otherwise what is wrong with it.
     */
    fun problem(file: File, name: String): String? {
        if (!file.isFile) return "missing"
        val size = file.length()
        if (size <= AmeliaRouting.MIN_GEO_SIZE || size >= MAX_SIZE) return "size $size"
        val scan = try {
            file.inputStream().buffered().use { scan(it, size) }
        } catch (e: java.io.IOException) {
            Scan(emptySet(), e.javaClass.simpleName)
        }
        return when {
            !scan.ok -> scan.error
            requiredCode(name) !in scan.codes -> "no ${requiredCode(name)}"
            else -> null
        }
    }

    /** [problem] with a log line for a damaged file. */
    fun check(file: File, name: String): Boolean {
        val p = problem(file, name) ?: return true
        LogUtil.w(AppConfig.TAG, "Amelia: geo $name is not usable: $p")
        return false
    }

    /**
     * Pure: [tmp] replaces [target] only when [valid]; a failed check or move (false or an
     * exception) leaves [target] as it was. [tmp] is gone afterwards either way. [move] must
     * replace atomically: rename(2) on Android does, so the core never sees a half-written file.
     */
    fun install(tmp: File, target: File, move: (File, File) -> Boolean = { from, to -> from.renameTo(to) }, valid: (File) -> Boolean): Boolean =
        try {
            valid(tmp) && move(tmp, target)
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }

    /**
     * The bases built into the APK (they come with libv2ray: full lists, RU and CATEGORY-RU
     * included) replace [names]; routing is written again for them. Blocking, any process.
     */
    fun restoreBuiltIn(context: Context, names: List<String> = FILES): Boolean {
        val dir = File(Utils.userAssetPath(context))
        if (!dir.isDirectory) return false
        val ok = names.all { name ->
            val tmp = File.createTempFile("$name.", ".builtin", dir)
            try {
                context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                install(tmp, File(dir, name)) { check(it, name) }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia: cannot restore the built-in $name", e)
                false
            } finally {
                tmp.delete()
            }
        }
        AmeliaRouting.apply(context)
        if (ok) {
            LogUtil.i(AppConfig.TAG, "Amelia: geo bases restored from the APK: $names")
            AmeliaJournal.add(context.getString(R.string.amelia_journal_geo_restored))
        }
        return ok
    }

    /** Pure: the bases present but damaged (a missing one is the downloader's job). */
    fun damaged(present: Map<String, Boolean>): List<String> = present.filter { (_, valid) -> !valid }.keys.toList()

    /** App start and the daily worker: damaged working bases are replaced by the built-in ones. */
    fun repairIfDamaged(context: Context): List<String> {
        val dir = File(Utils.userAssetPath(context))
        if (!dir.isDirectory) return emptyList()
        // leftovers of a download or restore killed half-way
        dir.listFiles { f -> f.name.endsWith(".download") || f.name.endsWith(".builtin") || f.name.endsWith(".tmp") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > STALE_TMP_MS }
            ?.forEach { it.delete() }
        val bad = damaged(FILES.filter { File(dir, it).exists() }.associateWith { check(File(dir, it), it) })
        if (bad.isNotEmpty()) restoreBuiltIn(context, bad)
        return bad
    }

    private const val STALE_TMP_MS = 10 * 60 * 1000L

    /**
     * Pure: an Xray start error caused by a geo base ("failed to check code RU from geoip.dat >
     * invalid body length", "failed to load geosite …").
     */
    fun isGeoError(text: String?): Boolean {
        val t = text?.lowercase() ?: return false
        return GEO_ERROR_MARKERS.any { it in t }
    }

    private val GEO_ERROR_MARKERS = listOf("geoip.dat", "geosite.dat", "common/geodata", "failed to load geo")

    /** Every message in [e]'s cause chain, for [isGeoError]. */
    fun errorText(e: Throwable): String = generateSequence(e) { it.cause }.take(8).joinToString(" > ") { it.toString() }

    /**
     * Runs [start]. When it fails on a geo base: [restore] (the built-in bases) and [start]
     * once more. Not a geo error, a failed restore or a second failure: the error goes on as
     * before (the second one carries the first as suppressed). Never more than two starts.
     */
    fun <T> withGeoRecovery(start: () -> T, restore: () -> Boolean): T {
        try {
            return start()
        } catch (e: Exception) {
            if (!isGeoError(errorText(e)) || !restore()) throw e
            try {
                return start()
            } catch (again: Exception) {
                again.addSuppressed(e)
                throw again
            }
        }
    }

    /**
     * GeoIPList / GeoSiteList protobuf starts with field 1, wire type 2 (0x0A). Rejects an HTML
     * error page served with 200, which would stop Xray from starting.
     */
    internal fun looksLikeGeoDat(file: File): Boolean =
        file.inputStream().use { it.read() } == 0x0A

    /** Daily background refresh, same process model as upstream's subscription worker. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        RemoteWorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // 1.7.2: a damaged base would stop the next VPN start: the built-in one replaces it
            repairIfDamaged(applicationContext)
            if (isDue(applicationContext)) update(applicationContext)
            // New files take effect on the next connect; no forced restart in the background.
            return Result.success()
        }
    }
}
