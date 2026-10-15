package com.v2ray.ang.amelia

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * In-app updates. The subscription server publishes `/app/version.json`:
 * ```
 * {"versionCode": 2, "versionName": "1.0.1",
 *  "url": "https://sub.ameliajustamelia.com:9443/app/AmeliaNet_1.0.1_universal.apk",
 *  "sha256": "<hex>", "size": 87000000, "notes": "Что нового", "minVersionCode": 1}
 * ```
 * The CI workflow generates this file for every release (notes from changelog/ru/<versionCode>.txt).
 * Field names are shared with the bot and the deploy script: never rename, only add.
 * minVersionCode is optional: an installed app below it must update before it may connect.
 * Android itself refuses an APK signed with a different key, the sha256 protects against
 * a broken or swapped download.
 */
object AmeliaUpdater {

    const val VERSION_PATH = "/app/version.json"
    private const val PREF_LAST_CHECK = "amelia_update_last_check"
    /** Last newer version seen, so a forced update survives app restarts without network. */
    private const val PREF_CACHED = "amelia_update_cached"
    private const val PREF_NOTIFIED = "amelia_update_notified"
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** True when the server says this installed version no longer works. */
    fun isRequired(v: AppVersion?, installed: Int = BuildConfig.VERSION_CODE): Boolean =
        v != null && v.versionCode > installed && (v.minVersionCode ?: 0) > installed

    /** Newer version from the last successful check, or null. */
    fun cached(): AppVersion? =
        parse(MmkvManager.decodeSettingsString(PREF_CACHED))?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    /** For the background notification: true once per new versionCode. */
    fun markNotified(v: AppVersion): Boolean {
        val last = MmkvManager.decodeSettingsString(PREF_NOTIFIED)?.toIntOrNull() ?: 0
        if (v.versionCode <= last) return false
        MmkvManager.encodeSettings(PREF_NOTIFIED, v.versionCode.toString())
        return true
    }

    /** APK downloads are accepted only from these hosts and their subdomains. */
    private val ALLOWED_HOSTS = listOf(
        "ameliajustamelia.com", "ameliajustamelia.net", "github.com", "githubusercontent.com",
    )

    data class AppVersion(
        val versionCode: Int = 0,
        val versionName: String? = null,
        val url: String? = null,
        val sha256: String? = null,
        val size: Long? = null,
        val notes: String? = null,
        /** Optional; absent in version.json made before 1.0.1. */
        val minVersionCode: Int? = null,
    )

    /** Validates server JSON; null when malformed or unsafe. */
    internal fun parse(json: String?): AppVersion? {
        if (json.isNullOrBlank()) return null
        val v = runCatching { JsonUtil.fromJson(json, AppVersion::class.java) }.getOrNull() ?: return null
        if (v.versionCode <= 0) return null
        if (v.url.isNullOrBlank() || !isAllowedUrl(v.url)) return null
        if (v.sha256 == null || !Regex("^[0-9a-fA-F]{64}$").matches(v.sha256)) return null
        // A bogus minVersionCode must not lock users out of a version that does not exist
        val min = v.minVersionCode?.takeIf { it in 1..v.versionCode }
        return v.copy(minVersionCode = min)
    }

    internal fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private const val PREF_LAST_ANSWER = "amelia_update_last_answer"

    /**
     * What the last check got, for "О программе": [ok] = false when no server answered;
     * otherwise the newest version the server offers (null when its version.json was invalid).
     */
    data class CheckAnswer(
        val at: Long = 0L,
        val ok: Boolean = false,
        val versionName: String? = null,
        val versionCode: Int? = null,
        val base: String? = null,
        val route: String? = null,
    )

    fun lastAnswer(): CheckAnswer? =
        MmkvManager.decodeSettingsString(PREF_LAST_ANSWER)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, CheckAnswer::class.java) }

    private fun saveAnswer(a: CheckAnswer) = MmkvManager.encodeSettings(PREF_LAST_ANSWER, JsonUtil.toJson(a))

    /** Epoch millis of the last successful check, 0 if never. */
    fun lastCheck(): Long = MmkvManager.decodeSettingsString(PREF_LAST_CHECK)?.toLongOrNull() ?: 0L

    fun isCheckDue(now: Long = System.currentTimeMillis()): Boolean {
        val last = MmkvManager.decodeSettingsString(PREF_LAST_CHECK)?.toLongOrNull() ?: 0L
        return now - last >= CHECK_INTERVAL_MS || last > now
    }

    /**
     * Blocking. Returns a version newer than the installed one, or null. A successful answer
     * also updates the cache (cleared when the installed version is current) and remembers
     * which address answered, so the APK is downloaded from it.
     */
    fun check(): AppVersion? {
        for (a in AmeliaHttp.currentPlan()) {
            try {
                AmeliaHttp.clientFor(a.route).newCall(AmeliaHttp.get(a.base + VERSION_PATH)).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val now = System.currentTimeMillis()
                    MmkvManager.encodeSettings(PREF_LAST_CHECK, now.toString())
                    AmeliaHttp.rememberBase(a.base)
                    val latest = parse(resp.body.string())
                    saveAnswer(CheckAnswer(now, true, latest?.versionName, latest?.versionCode, a.base, a.route.name))
                    val v = latest?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                    MmkvManager.encodeSettings(PREF_CACHED, v?.let { JsonUtil.toJson(it) } ?: "")
                    return v
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia: update check failed at ${a.base} (${a.route})", e)
            }
        }
        saveAnswer(CheckAnswer(System.currentTimeMillis(), false))
        return null
    }

    /**
     * Where to download the APK from, in order. An APK on our domains is fetched from the
     * address that answered version.json first ([lastBase]), then from the other addresses;
     * through the tunnel first when the VPN is on. Other hosts (GitHub) are used as given.
     */
    internal fun downloadPlan(url: String, lastBase: String?, tunnel: Boolean): List<Pair<String, AmeliaHttp.Route>> {
        if (!AmeliaHttp.isOwnUrl(url)) {
            return AmeliaHttp.plan(listOf(url), tunnel).map { it.base to it.route }
        }
        return AmeliaHttp.plan(AmeliaHttp.orderBases(lastBase), tunnel)
            .map { AmeliaHttp.rewriteOrigin(url, it.base) to it.route }
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Blocking download with sha256 check, walking [downloadPlan]. Removes older downloads.
     * @param onProgress 0..100, or -1 when the size is unknown
     */
    fun download(context: Context, v: AppVersion, onProgress: (Int) -> Unit): File? {
        val dir = updatesDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "AmeliaNet-${v.versionCode}.apk")
        for ((url, route) in downloadPlan(v.url!!, AmeliaHttp.lastBase(), AmeliaHttp.tunnelAvailable())) {
            if (!isAllowedUrl(url)) continue
            if (downloadFrom(url, route, v, target, onProgress)) {
                AmeliaConfig.SUB_BASE_URLS.firstOrNull { url.startsWith(it) }?.let { AmeliaHttp.rememberBase(it) }
                return target
            }
        }
        return null
    }

    private fun downloadFrom(
        url: String,
        route: AmeliaHttp.Route,
        v: AppVersion,
        target: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            AmeliaHttp.clientFor(route).newCall(AmeliaHttp.get(url)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Amelia: update download HTTP ${resp.code} ($route)")
                    return false
                }
                // Redirects (e.g. GitHub → CDN) must stay on allowed hosts too
                if (!isAllowedUrl(resp.request.url.toString())) return false
                val total = resp.body.contentLength().takeIf { it > 0 } ?: v.size ?: -1L
                val digest = MessageDigest.getInstance("SHA-256")
                resp.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -2
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(v.sha256, ignoreCase = true)) {
                    LogUtil.w(AppConfig.TAG, "Amelia: update sha256 mismatch ($route)")
                    tmp.delete()
                    return false
                }
            }
            return tmp.renameTo(target)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: update download failed ($route)", e)
            tmp.delete()
            return false
        }
    }

    /** Android 8+: the user must allow this app to install unknown apps once. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
