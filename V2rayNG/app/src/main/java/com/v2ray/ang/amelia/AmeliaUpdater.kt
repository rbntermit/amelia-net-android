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
 *  "sha256": "<hex>", "size": 87000000, "notes": "Что нового"}
 * ```
 * The CI workflow generates this file for every release. Android itself refuses an APK
 * signed with a different key, the sha256 protects against a broken or swapped download.
 */
object AmeliaUpdater {

    const val VERSION_PATH = "/app/version.json"
    private const val PREF_LAST_CHECK = "amelia_update_last_check"
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

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
    )

    /** Validates server JSON; null when malformed or unsafe. */
    internal fun parse(json: String?): AppVersion? {
        val v = json?.let { runCatching { JsonUtil.fromJson(it, AppVersion::class.java) }.getOrNull() } ?: return null
        if (v.versionCode <= 0) return null
        if (v.url.isNullOrBlank() || !isAllowedUrl(v.url)) return null
        if (v.sha256 == null || !Regex("^[0-9a-fA-F]{64}$").matches(v.sha256)) return null
        return v
    }

    internal fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isCheckDue(now: Long = System.currentTimeMillis()): Boolean {
        val last = MmkvManager.decodeSettingsString(PREF_LAST_CHECK)?.toLongOrNull() ?: 0L
        return now - last >= CHECK_INTERVAL_MS || last > now
    }

    /** Blocking. Returns a version newer than the installed one, or null. */
    fun check(): AppVersion? {
        for (base in AmeliaConfig.SUB_BASE_URLS) {
            try {
                AmeliaHttp.client.newCall(AmeliaHttp.get(base + VERSION_PATH)).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    MmkvManager.encodeSettings(PREF_LAST_CHECK, System.currentTimeMillis().toString())
                    val v = parse(resp.body.string()) ?: return null
                    return v.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Amelia: update check failed at $base", e)
            }
        }
        return null
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Blocking download with sha256 check. Removes older downloads.
     * @param onProgress 0..100, or -1 when the size is unknown
     */
    fun download(context: Context, v: AppVersion, onProgress: (Int) -> Unit): File? {
        val dir = updatesDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "AmeliaNet-${v.versionCode}.apk")
        val tmp = File(dir, "${target.name}.part")
        try {
            AmeliaHttp.client.newCall(AmeliaHttp.get(v.url!!)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Amelia: update download HTTP ${resp.code}")
                    return null
                }
                // Redirects (e.g. GitHub → CDN) must stay on allowed hosts too
                if (!isAllowedUrl(resp.request.url.toString())) return null
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
                    LogUtil.w(AppConfig.TAG, "Amelia: update sha256 mismatch")
                    tmp.delete()
                    return null
                }
            }
            return if (tmp.renameTo(target)) target else null
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: update download failed", e)
            tmp.delete()
            return null
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
