package com.v2ray.ang.amelia

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash reports (1.3.0): uncaught exceptions in every process (app, VPN daemon, workers) and a
 * few non-fatal failures (core start, VPN service, update install) are written to a file and
 * sent with the next app start to POST /app/crash, around the tunnel like the problem report.
 * At most [MAX_PER_HOUR] a hour; send errors are ignored. Personal data is removed first
 * ([scrub]). Switch "Отправлять отчёты о сбоях", on by default.
 */
object AmeliaCrash {

    const val PATH = "/app/crash"
    const val PREF_ENABLED = "amelia_crash_enabled"
    private const val PREF_SENT = "amelia_crash_sent"
    const val MAX_STACK = 8_000
    const val MAX_PER_HOUR = 5
    private const val MAX_FILES = 20
    private const val DIR = "crashes"

    /** Our servers: their addresses are not personal and help to read a report (list: AmeliaConfig). */
    val OUR_IPS: Set<String> get() = AmeliaConfig.OUR_SERVER_IPS

    /** 1.7.3: our IPv6 addresses in one spelling ([ipv6Key]), so "2a05:0541:…" is found too. */
    private val OUR_IPV6: Set<String> by lazy { OUR_IPS.filter { it.contains(':') }.mapNotNull(::ipv6Key).toSet() }

    data class Crash(
        val iid: String = "",
        val ts: Long = 0L,
        val app: String = "",
        val code: Int = 0,
        val xray: String = "",
        val android: String = "",
        val device: String = "",
        val thread: String = "",
        val where: String = "",
        val fatal: Boolean = false,
        val stack: String = "",
    )

    // ---- scrubbing (pure, unit-tested) ----------------------------------------------------

    private val LINK = Regex("""(?i)\b(vless|vmess|trojan|ss|ssr|hysteria2?|hy2|tuic|wireguard|socks|amelia)://\S+""")
    private val SUB_URL = Regex("""(?i)\bhttps?://[^\s/]+(?::\d+)?/sub/\S*""")
    private val UUID = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val HEX16 = Regex("""(?i)(?<![0-9a-z])[0-9a-f]{16}(?![0-9a-z])""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    /** 1.7.0: "user:pass@" in any address (http, socks, a proxy line in an exception). */
    private val USERINFO = Regex("""(?:(?<=://)|(?<=[\s"'(=])|^)[^\s@/:"'()]+:[^\s@/"'()]+@""", RegexOption.MULTILINE)
    /** 1.7.0: passwords and secrets written as `password: …`, `"pass":"…"`, `psk=…` (trojan, shadowsocks, wireguard). */
    private val SECRET = Regex("""(?i)("?\b(?:password|passwd|pass|psk|secret|private_?key|pre_?shared_?key|auth)"?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;}\]]+)""")
    /** 1.7.0: IPv6 candidates: hex groups with colons; [isIpv6] decides. */
    private val IPV6 = Regex("""(?i)(?<![0-9a-z:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-z:])""")

    /**
     * 1.7.0, pure: an IPv6 address, not a clock ("12:34:56") or a Kotlin "::": it has "::" or
     * at least 4 colons, and at least two hex groups.
     */
    internal fun isIpv6(s: String): Boolean {
        val groups = s.split(':').filter { it.isNotEmpty() }
        return (s.contains("::") || s.count { it == ':' } >= 4) && groups.size >= 2
    }

    /**
     * 1.7.3, pure: [s] as eight lowercase groups without leading zeros ("2a05:541:142:1aa::1" and
     * "2A05:0541:0142:01AA:0:0:0:1" give the same), or null when it is not an IPv6 address.
     * A zone ("%wlan0") is dropped.
     */
    internal fun ipv6Key(s: String): String? {
        val a = s.substringBefore('%').lowercase()
        if (!isIpv6(a) || a.count { it == ':' } > 7) return null
        val parts = a.split("::")
        if (parts.size > 2) return null
        fun groups(p: String) = if (p.isEmpty()) emptyList() else p.split(':')
        val head = groups(parts[0])
        val tail = if (parts.size == 2) groups(parts[1]) else emptyList()
        val missing = 8 - head.size - tail.size
        if (if (parts.size == 1) missing != 0 else missing < 1) return null
        val all = head + List(missing) { "0" } + tail
        if (all.any { g -> g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' } }) return null
        return all.joinToString(":") { it.trimStart('0').ifEmpty { "0" } }
    }

    /** 1.7.3: one of our servers' IPv6 addresses ([OUR_IPS]). */
    internal fun isOurIpv6(s: String): Boolean = ipv6Key(s)?.let { it in OUR_IPV6 } == true

    /**
     * Removes the subscription key ([key] and any 16-hex word), UUIDs, proxy and subscription
     * links, and IPv4 addresses other than [OUR_IPS]. 1.7.0: also IPv6 addresses, passwords
     * (trojan, shadowsocks and other `password: …` forms) and any `user:pass@`. 1.7.3: our own
     * IPv6 addresses ([OUR_IPS]) stay, like our IPv4 ones.
     */
    fun scrub(text: String, key: String? = null): String {
        var s = text
        if (!key.isNullOrBlank() && key.length >= 4) s = s.replace(key, "<key>")
        s = LINK.replace(s, "<link>")
        s = SUB_URL.replace(s, "<sub-url>")
        s = USERINFO.replace(s, "<user>@")
        s = SECRET.replace(s) { it.groupValues[1] + "<secret>" }
        s = UUID.replace(s, "<uuid>")
        s = HEX16.replace(s, "<key>")
        s = IPV4.replace(s) { if (it.value in OUR_IPS) it.value else "<ip>" }
        s = IPV6.replace(s) { if (!isIpv6(it.value) || isOurIpv6(it.value)) it.value else "<ip6>" }
        return s
    }

    fun stackOf(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** Fewer than [MAX_PER_HOUR] sends in the last hour, given the send times. */
    fun canSend(sentAt: List<Long>, now: Long): Boolean = sentAt.count { now - it in 0 until 3_600_000L } < MAX_PER_HOUR

    // ---- capture --------------------------------------------------------------------------

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, true)

    fun setEnabled(enabled: Boolean) = MmkvManager.encodeSettings(PREF_ENABLED, enabled)

    @Volatile private var appContext: Context? = null

    /** Called by AngApplication in every process. */
    fun install(app: Application) {
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                save(app, e, thread.name, where = "", fatal = true)
            } catch (_: Throwable) {
                // never make a crash worse
            }
            if (previous != null) previous.uncaughtException(thread, e) else Process.killProcess(Process.myPid())
        }
    }

    /** A failure the app survived: "core_start", "vpn_service", "update"… */
    fun nonFatal(where: String, e: Throwable?, message: String? = null) {
        val ctx = appContext ?: return
        try {
            val t = e ?: RuntimeException(message ?: where)
            save(ctx, t, Thread.currentThread().name, where, fatal = false, extra = message?.takeIf { e != null })
        } catch (x: Throwable) {
            LogUtil.w(AppConfig.TAG, "Amelia: cannot save a crash report", x)
        }
    }

    private fun processName(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else File("/proc/self/cmdline").readText().trim('\u0000', ' ')
    } catch (_: Exception) {
        "?"
    }

    private fun save(context: Context, e: Throwable, thread: String, where: String, fatal: Boolean, extra: String? = null) {
        if (!isEnabled()) return
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.size >= MAX_FILES) files.take(files.size - MAX_FILES + 1).forEach { it.delete() }
        // the process tells whether it was the app, the VPN daemon or a background worker
        val stack = buildString {
            append("process: ").append(processName()).append('\n')
            extra?.let { append(it).append('\n') }
            append(stackOf(e))
        }
        val crash = Crash(
            iid = AmeliaProbe.installId(),
            ts = System.currentTimeMillis() / 1000,
            app = BuildConfig.VERSION_NAME,
            code = BuildConfig.VERSION_CODE,
            xray = AmeliaVersions.xrayCachedOrBuild(),
            android = Build.VERSION.RELEASE.orEmpty(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            thread = thread,
            where = where,
            fatal = fatal,
            stack = stack,
        )
        File(dir, "${System.currentTimeMillis()}-${Process.myPid()}.json").writeText(AmeliaProbe.wireGson.toJson(crash))
    }

    // ---- sending --------------------------------------------------------------------------

    private fun sentTimes(): List<Long> =
        MmkvManager.decodeSettingsString(PREF_SENT)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    /** Blocking; at app start. Sends what was saved, oldest first, within the hourly limit. */
    fun sendPending(context: Context) {
        val dir = File(context.filesDir, DIR)
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        if (!isEnabled()) {
            files.forEach { it.delete() }
            return
        }
        val key = AmeliaManager.getKey()
        for (f in files) {
            val now = System.currentTimeMillis()
            val sent = sentTimes()
            if (!canSend(sent, now)) return
            val crash = runCatching { AmeliaProbe.wireGson.fromJson(f.readText(), Crash::class.java) }.getOrNull()
            if (crash == null) {
                f.delete()
                continue
            }
            val clean = crash.copy(stack = scrub(crash.stack, key).take(MAX_STACK), thread = scrub(crash.thread, key))
            val answer = AmeliaNetwork.post(context, PATH) { AmeliaProbe.wireGson.toJson(clean) }
            if (answer == null) return // no network now: next start
            f.delete()
            MmkvManager.encodeSettings(PREF_SENT, (sent.filter { now - it < 3_600_000L } + now).joinToString(","))
        }
    }
}
