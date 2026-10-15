package com.v2ray.ang.amelia

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils
import java.io.File
import java.net.URI

/**
 * Facts for "О программе" and the "Скопировать для поддержки" text. Never contains the
 * subscription key, location UUIDs or the installation id.
 */
object AmeliaAbout {

    data class Info(
        val versionName: String,
        val versionCode: Int,
        /** Version reported by the core itself (or the build tag, see [xrayFromRuntime]). */
        val xray: String,
        val xrayFromRuntime: Boolean,
        val hev: String,
        /** File dates, epoch millis; 0 = not downloaded. */
        val geoipAt: Long,
        val geositeAt: Long,
        val subUpdatedAt: Long,
        /** Address that served the subscription last, and whether through the VPN. */
        val subBase: String?,
        val subViaTunnel: Boolean,
        val updateAnswer: AmeliaUpdater.CheckAnswer?,
        val bypassRu: Boolean,
        val bypassCount: Int,
        val probeEnabled: Boolean,
        val net: AmeliaNetwork.Info,
    )

    /** Blocking: may load the native library once to ask Xray-core its version. */
    fun load(context: Context): Info {
        val dir = Utils.userAssetPath(context)
        fun fileAt(name: String) = File(dir, name).takeIf { it.isFile }?.lastModified() ?: 0L
        val source = AmeliaManager.lastSubscriptionSource()
        return Info(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            xray = AmeliaVersions.xray(),
            xrayFromRuntime = AmeliaVersions.xrayFromRuntime(),
            hev = AmeliaVersions.hevTunnel,
            geoipAt = fileAt(AppConfig.GEOIP_DAT),
            geositeAt = fileAt(AppConfig.GEOSITE_DAT),
            subUpdatedAt = AmeliaManager.lastSubscriptionUpdate(),
            subBase = source?.first,
            subViaTunnel = source?.second == AmeliaHttp.Route.TUNNEL,
            updateAnswer = AmeliaUpdater.lastAnswer(),
            bypassRu = AmeliaApps.isEnabled(),
            bypassCount = AmeliaApps.bypassed().size,
            probeEnabled = AmeliaProbe.isEnabled(),
            net = AmeliaNetwork.info(context),
        )
    }

    /**
     * Short name of one of our addresses: "msk1", "msk2", or "запасной (<host>)" for the
     * main and backup domains. Unknown addresses are shown by host.
     */
    fun baseLabel(base: String?, backupWord: String): String? {
        val host = base?.let { runCatching { URI(it).host }.getOrNull() } ?: return null
        val first = host.substringBefore('.')
        return if (first.startsWith("msk")) first else "$backupWord ($host)"
    }

    fun dateOrDash(ms: Long): String = if (ms > 0) AmeliaFormat.dateTime(ms) else "—"

    /** What the update server said, in words. */
    fun updateAnswerText(context: Context, a: AmeliaUpdater.CheckAnswer?): String = when {
        a == null || a.at <= 0 -> context.getString(R.string.amelia_about_update_never)
        !a.ok -> context.getString(R.string.amelia_about_update_no_answer, AmeliaFormat.dateTime(a.at))
        a.versionCode == null -> context.getString(R.string.amelia_about_update_bad, AmeliaFormat.dateTime(a.at))
        else -> context.getString(
            R.string.amelia_about_update_answer,
            AmeliaFormat.dateTime(a.at),
            a.versionName ?: a.versionCode.toString(),
            a.versionCode,
            baseLabel(a.base, context.getString(R.string.amelia_about_backup)) ?: "—",
        )
    }

    fun subText(context: Context, i: Info): String = if (i.subUpdatedAt <= 0) {
        context.getString(R.string.amelia_settings_sub_never)
    } else {
        context.getString(
            if (i.subViaTunnel) R.string.amelia_about_sub_tunnel else R.string.amelia_about_sub_direct,
            AmeliaFormat.dateTime(i.subUpdatedAt),
            baseLabel(i.subBase, context.getString(R.string.amelia_about_backup)) ?: "—",
        )
    }

    /** Everything on the screen as one text for support. */
    fun supportText(context: Context, i: Info): String = buildString {
        appendLine(context.getString(R.string.amelia_version_full, i.versionName, i.versionCode))
        appendLine(context.getString(R.string.amelia_about_xray, i.xray))
        appendLine(context.getString(R.string.amelia_about_hy2, i.xray))
        appendLine(context.getString(R.string.amelia_about_hev, i.hev))
        appendLine(context.getString(R.string.amelia_about_android, Build.VERSION.RELEASE.orEmpty(), "${Build.MANUFACTURER} ${Build.MODEL}"))
        appendLine(context.getString(R.string.amelia_about_geoip, dateOrDash(i.geoipAt)))
        appendLine(context.getString(R.string.amelia_about_geosite, dateOrDash(i.geositeAt)))
        appendLine(subText(context, i))
        appendLine(updateAnswerText(context, i.updateAnswer))
        appendLine(
            context.getString(
                R.string.amelia_about_network, i.net.net + if (i.net.op.isNotBlank()) ", ${i.net.op}" else "",
            ),
        )
        appendLine(
            if (i.bypassRu) context.getString(R.string.amelia_about_bypass_on, i.bypassCount)
            else context.getString(R.string.amelia_about_bypass_off),
        )
        appendLine(
            context.getString(if (i.probeEnabled) R.string.amelia_about_probe_on else R.string.amelia_about_probe_off),
        )
        append(AmeliaConfig.SOURCE_CODE_URL)
    }
}
