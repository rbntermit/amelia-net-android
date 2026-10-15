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
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
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

    /** Through the tunnel first when the VPN is on, then directly; last good address first. */
    private fun downloadOne(dir: File, name: String): Boolean {
        for (a in AmeliaHttp.currentPlan()) {
            val tmp = File(dir, "$name.tmp")
            try {
                val ok = AmeliaHttp.clientFor(a.route)
                    .newCall(AmeliaHttp.get(a.base + AmeliaConfig.GEO_PATH + name)).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            LogUtil.w(AppConfig.TAG, "Amelia: geo $name HTTP ${resp.code} from ${a.base} (${a.route})")
                            false
                        } else {
                            resp.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            true
                        }
                    }
                val size = tmp.length()
                if (ok && tmp.isFile && size > AmeliaRouting.MIN_GEO_SIZE && size < MAX_SIZE && looksLikeGeoDat(tmp)) {
                    // rename() replaces atomically, so the core never sees a half-written file
                    if (tmp.renameTo(File(dir, name))) {
                        AmeliaHttp.rememberBase(a.base)
                        return true
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia: geo $name download failed from ${a.base} (${a.route})", e)
            } finally {
                tmp.delete()
            }
        }
        return false
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
            if (isDue(applicationContext)) update(applicationContext)
            // New files take effect on the next connect; no forced restart in the background.
            return Result.success()
        }
    }
}
