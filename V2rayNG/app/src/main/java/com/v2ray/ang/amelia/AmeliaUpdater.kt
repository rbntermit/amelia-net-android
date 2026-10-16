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

    /**
     * For the background notification: true once per new versionCode, and once more when the
     * update downloaded on Wi-Fi is ready to install ("<code>:<ready 0/1>").
     */
    fun markNotified(v: AppVersion, ready: Boolean = false): Boolean {
        val stored = MmkvManager.decodeSettingsString(PREF_NOTIFIED).orEmpty().split(':')
        val lastCode = stored.getOrNull(0)?.toIntOrNull() ?: 0
        val lastReady = stored.getOrNull(1) == "1"
        if (!shouldNotify(v.versionCode, ready, lastCode, lastReady)) return false
        MmkvManager.encodeSettings(PREF_NOTIFIED, "${v.versionCode}:${if (ready) 1 else 0}")
        return true
    }

    internal fun shouldNotify(code: Int, ready: Boolean, lastCode: Int, lastReady: Boolean): Boolean =
        code > lastCode || (code == lastCode && ready && !lastReady)

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
        /**
         * 1.3.0: smaller per-ABI APKs next to the universal one in /app/ (file name only):
         * {"arm64-v8a": {"file": "AmeliaNet_1.3.0_arm64-v8a.apk", "sha256": "…", "size": 32400000}}
         */
        val abis: Map<String, AbiApk>? = null,
    )

    data class AbiApk(val file: String? = null, val sha256: String? = null, val size: Long? = null)

    /** One APK to try: where, its checksum and size. */
    data class Apk(val url: String, val sha256: String, val size: Long?)

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val APK_FILE = Regex("""^[A-Za-z0-9._-]+\.apk$""")

    /**
     * APKs to try, in order: the arm64-v8a one when the phone runs arm64 and version.json has
     * it (about 31 MB instead of 75), from the address that answered version.json; then the
     * universal one from "url".
     */
    fun apkChoices(v: AppVersion, supportedAbis: List<String>, base: String?): List<Apk> = buildList {
        val arm = v.abis?.get(ARM64)
        val origin = base ?: v.url?.let { runCatching { URI(it) }.getOrNull() }?.let { "${it.scheme}://${it.rawAuthority}" }
        if (ARM64 in supportedAbis && arm != null && origin != null &&
            arm.file != null && APK_FILE.matches(arm.file) && arm.sha256 != null && SHA256.matches(arm.sha256)
        ) {
            add(Apk("${origin.trimEnd('/')}/app/${arm.file}", arm.sha256, arm.size))
        }
        v.url?.let { url -> v.sha256?.let { add(Apk(url, it, v.size)) } }
    }

    const val ARM64 = "arm64-v8a"

    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    /** Size of what will be downloaded on this phone, for "Скачать · 31 МБ". */
    fun downloadSize(v: AppVersion): Long? = apkChoices(v, deviceAbis(), AmeliaHttp.lastBase()).firstOrNull()?.size

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
                // 1.3.1: always from the server: no HTTP cache, and ask proxies on the way not to answer from theirs
                val request = AmeliaHttp.get(a.base + VERSION_PATH).newBuilder()
                    .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build()
                AmeliaHttp.clientFor(a.route).newCall(request).execute().use { resp ->
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
        // 1.3.1: a version remembered from an earlier check is not shown again without a fresh answer;
        // only a required update (minVersionCode) holds, so it cannot be skipped by going offline
        if (cached()?.let { isRequired(it) } != true) MmkvManager.encodeSettings(PREF_CACHED, "")
        return null
    }

    /** When the last check was attempted (answered or not), 0 if never. */
    fun lastAttempt(): Long = lastAnswer()?.at ?: 0L

    /** Coming back to the screen checks again after this long. */
    const val RESUME_CHECK_MS = 60L * 60 * 1000

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
     * Blocking download with sha256 check, walking [downloadPlan]. 1.3.1: an interrupted download
     * continues where it stopped (HTTP Range; msk1/msk2 serve the APK in parts), files of other
     * versions are removed, and only one process downloads at a time.
     * @param onProgress 0..100, or -1 when the size is unknown
     */
    fun download(context: Context, v: AppVersion, onProgress: (Int) -> Unit): File? {
        val dir = updatesDir(context)
        val lock = try {
            java.io.RandomAccessFile(File(dir, ".lock"), "rw").channel.tryLock()
        } catch (e: Exception) {
            null
        } ?: return null // the background worker or the app is downloading it right now
        try {
            pruneDownloads(context, v)
            val target = targetFile(context, v)
            // arm64-v8a APK first (1.3.0), the universal one if that fails or does not match
            for (apk in apkChoices(v, deviceAbis(), AmeliaHttp.lastBase())) {
                for ((url, route) in downloadPlan(apk.url, AmeliaHttp.lastBase(), AmeliaHttp.tunnelAvailable())) {
                    if (!isAllowedUrl(url)) continue
                    if (downloadFrom(url, route, apk, partFile(context, v, apk), target, onProgress)) {
                        AmeliaConfig.SUB_BASE_URLS.firstOrNull { url.startsWith(it) }?.let { AmeliaHttp.rememberBase(it) }
                        return target
                    }
                }
            }
            return null
        } finally {
            runCatching { lock.release(); lock.channel().close() }
        }
    }

    private fun targetFile(context: Context, v: AppVersion) = File(updatesDir(context), "AmeliaNet-${v.versionCode}.apk")

    /** Partial download of one APK; named by its checksum, so any mirror can continue it. */
    private fun partFile(context: Context, v: AppVersion, apk: Apk) =
        File(updatesDir(context), "AmeliaNet-${v.versionCode}-${apk.sha256.take(16).lowercase()}.part")

    /** Files of [v] that are kept: its APK and its partial downloads. */
    internal fun belongsTo(fileName: String, versionCode: Int): Boolean =
        fileName == ".lock" || fileName.startsWith("AmeliaNet-$versionCode.") || fileName.startsWith("AmeliaNet-$versionCode-")

    /** A newer version came out meanwhile: what was downloaded for the older one is deleted. */
    fun pruneDownloads(context: Context, v: AppVersion) {
        updatesDir(context).listFiles()?.filterNot { belongsTo(it.name, v.versionCode) }?.forEach { it.delete() }
    }

    /**
     * An APK for [v] downloaded earlier (e.g. in the background on Wi-Fi) whose checksum matches
     * one of its APKs; null otherwise. Blocking (hashes the file).
     */
    fun readyApk(context: Context, v: AppVersion): File? {
        val f = targetFile(context, v).takeIf { it.isFile } ?: return null
        val hex = sha256(f)
        return f.takeIf { apkChoices(v, deviceAbis(), AmeliaHttp.lastBase()).any { it.sha256.equals(hex, ignoreCase = true) } }
    }

    private fun sha256(f: File): String = hex(MessageDigest.getInstance("SHA-256").also { update(it, f) })

    private fun update(digest: MessageDigest, f: File) {
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }

    /** After an update is installed: the downloaded APK is no longer needed. */
    fun deleteDownloads(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    /** Wi-Fi or another network without a data limit: updates may be downloaded unasked. */
    fun isUnmetered(context: Context): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false

    /**
     * What to do with the server's answer to a request for the rest of the file from [have]:
     * 206 = append, 200 = the server sent the whole file (start over), 416 = we may already
     * have all of it.
     */
    internal enum class Resume { APPEND, RESTART, CHECK_COMPLETE, FAIL }

    internal fun resumeAction(have: Long, code: Int): Resume = when {
        have > 0 && code == 206 -> Resume.APPEND
        code == 200 -> Resume.RESTART
        have > 0 && code == 416 -> Resume.CHECK_COMPLETE
        else -> Resume.FAIL
    }

    private fun downloadFrom(
        url: String,
        route: AmeliaHttp.Route,
        apk: Apk,
        part: File,
        target: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val have = if (part.isFile) part.length() else 0L
        try {
            val request = AmeliaHttp.get(url).newBuilder()
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            AmeliaHttp.clientFor(route).newCall(request).execute().use { resp ->
                // Redirects (e.g. GitHub → CDN) must stay on allowed hosts too
                if (!isAllowedUrl(resp.request.url.toString())) return false
                val digest = MessageDigest.getInstance("SHA-256")
                var done = when (resumeAction(have, resp.code)) {
                    Resume.APPEND -> {
                        update(digest, part)
                        have
                    }
                    Resume.RESTART -> {
                        part.delete()
                        0L
                    }
                    Resume.CHECK_COMPLETE -> return finish(part, target, apk, sha256(part), route)
                    Resume.FAIL -> {
                        LogUtil.w(AppConfig.TAG, "Amelia: update download HTTP ${resp.code} ($route)")
                        return false
                    }
                }
                val total = apk.size ?: (resp.body.contentLength().takeIf { it > 0 }?.let { it + done } ?: -1L)
                resp.body.byteStream().use { input ->
                    java.io.FileOutputStream(part, done > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
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
                return finish(part, target, apk, hex(digest), route)
            }
        } catch (e: Exception) {
            // the part stays: the next attempt (this or another mirror, now or later) continues it
            LogUtil.w(AppConfig.TAG, "Amelia: update download interrupted ($route)", e)
            return false
        }
    }

    private fun finish(part: File, target: File, apk: Apk, hex: String, route: AmeliaHttp.Route): Boolean {
        if (!hex.equals(apk.sha256, ignoreCase = true)) {
            LogUtil.w(AppConfig.TAG, "Amelia: update sha256 mismatch ($route)")
            part.delete()
            return false
        }
        target.delete()
        return part.renameTo(target)
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
