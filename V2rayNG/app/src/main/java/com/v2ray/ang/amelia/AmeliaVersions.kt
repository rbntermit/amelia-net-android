package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Versions of the app and its cores, for "О программе", the problem report and the User-Agent.
 *
 * Xray-core reports its own version at runtime (libv2ray `CheckVersionX`). Loading the native
 * library just for that is not free, so the answer is cached per app version in MMKV, shared
 * by all processes. hysteria2 is not a separate plugin: Xray-core implements it, so its
 * version is Xray's. hev-socks5-tunnel has no version call; CI builds the tag from deps.env.
 */
object AmeliaVersions {

    private const val PREF_XRAY = "amelia_xray_version"

    /** "Lib v39, Xray-core v26.10.10 (...)" → "26.10.10" */
    internal fun parseXray(text: String?): String? =
        text?.let { Regex("""Xray-core v?([0-9][0-9A-Za-z.\-]*)""").find(it)?.groupValues?.get(1) }

    /** Build-time fallback: the AndroidLibXrayLite tag from deps.env without "v". */
    private val buildXray: String get() = BuildConfig.XRAY_TAG.removePrefix("v")

    /** Xray-core version reported by the core itself; build-time tag if the core cannot be asked. */
    fun xray(): String {
        val key = "${BuildConfig.VERSION_CODE}:"
        MmkvManager.decodeSettingsString(PREF_XRAY)?.takeIf { it.startsWith(key) }?.let { return it.removePrefix(key) }
        val runtime = try {
            parseXray(CoreNativeManager.getLibVersion())
        } catch (e: Throwable) {
            // UnsatisfiedLinkError is an Error, not an Exception
            LogUtil.w(AppConfig.TAG, "Amelia: cannot read the Xray-core version", e)
            null
        }
        if (runtime != null) MmkvManager.encodeSettings(PREF_XRAY, key + runtime)
        return runtime ?: buildXray
    }

    /** True when [xray] came from the running core rather than from the build. */
    fun xrayFromRuntime(): Boolean =
        MmkvManager.decodeSettingsString(PREF_XRAY)?.startsWith("${BuildConfig.VERSION_CODE}:") == true

    /** hysteria2 is built into Xray-core. Sent as "xray-<version>" in the report's "hy2" field. */
    fun hysteria2(): String = "xray-${xray()}"

    val hevTunnel: String get() = BuildConfig.HEV_VERSION

    /** 1.8.2: the server keeps only the first 200 characters of a User-Agent. */
    const val MAX_USER_AGENT = 200

    /**
     * `AmeliaNet/<versionName> v2rayNG/<upstream> Xray/<core> Pick/<manual|auto>`. The bot counts
     * installations by the leading `AmeliaNet/<version>` and reads the core from `Xray/<version>`:
     * never change those parts. 1.8.2: `Pick/manual` when the current location was chosen by hand
     * and the app does not change it (AmeliaManual.isManual), otherwise `Pick/auto`; the bot marks
     * such people with ✋ in "Кто онлайн". Built on every request, never cached.
     */
    fun userAgent(xray: String = xrayCachedOrBuild(), manual: Boolean = manualPick()): String =
        userAgentOf(BuildConfig.VERSION_NAME, BuildConfig.UPSTREAM_VERSION, xray, manual)

    /**
     * Pure, 1.8.2: the variable parts are cut so the whole line stays within [MAX_USER_AGENT] and
     * the `Pick/` token at the end is never lost (at most 165 characters).
     */
    internal fun userAgentOf(version: String, upstream: String, xray: String, manual: Boolean): String =
        "AmeliaNet/${version.take(32)} v2rayNG/${upstream.take(32)} Xray/${xray.take(64)} Pick/${if (manual) "manual" else "auto"}"

    /** 1.8.2: the selected location was chosen by hand (MMKV, any process); false when unknown. */
    private fun manualPick(): Boolean = try {
        AmeliaManual.isManual(MmkvManager.getSelectServer())
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Amelia: cannot read the manual choice for the User-Agent", e)
        false
    }

    /** Never loads the native library: the User-Agent is needed in every process, often. */
    fun xrayCachedOrBuild(): String {
        val key = "${BuildConfig.VERSION_CODE}:"
        return MmkvManager.decodeSettingsString(PREF_XRAY)?.takeIf { it.startsWith(key) }?.removePrefix(key)
            ?: buildXray
    }
}
