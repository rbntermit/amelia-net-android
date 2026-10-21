package com.v2ray.ang.amelia

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/**
 * 1.5.2: the routing profile the subscription sends in the `routing` header,
 * `happ://routing/onadd/<base64 JSON>` (sub_server, happ_routing_for_app.py). The app takes
 * the site and IP lists from it (ProxySites/ProxyIp, DirectSites/DirectIp); DNS, geo URLs
 * and the rest stay as built in. The last profile that parsed is kept: a missing or broken
 * header changes nothing.
 */
object AmeliaRouteProfile {

    data class Profile(
        val proxySites: List<String> = emptyList(),
        val proxyIp: List<String> = emptyList(),
        val directSites: List<String> = emptyList(),
        val directIp: List<String> = emptyList(),
    )

    const val HEADER = "routing"
    private const val PREF = "amelia_routing_profile"
    private val PREFIXES = listOf("happ://routing/onadd/", "happ://routing/add/")

    // ---- pure logic (unit-tested) ---------------------------------------------------------

    /**
     * The profile from the header value, or null when there is none or it does not parse.
     * [decodeBase64] is injected so JVM tests do not need android.util.Base64.
     */
    fun parse(header: String?, decodeBase64: (String) -> String?): Profile? {
        val v = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val prefix = PREFIXES.firstOrNull { v.startsWith(it, ignoreCase = true) }
        val payload = (if (prefix != null) v.substring(prefix.length) else v).trim().trimEnd('/')
        val json = if (payload.startsWith("{")) payload else decodeBase64(toStandardBase64(payload)) ?: return null
        val obj = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val p = Profile(
            proxySites = list(obj, "ProxySites"),
            proxyIp = list(obj, "ProxyIp"),
            directSites = list(obj, "DirectSites"),
            directIp = list(obj, "DirectIp"),
        )
        // a profile without any of the four lists is not ours: keep the previous rules
        val known = listOf("ProxySites", "ProxyIp", "DirectSites", "DirectIp").any { key(obj, it) != null }
        return if (known) p else null
    }

    /** URL-safe alphabet and missing padding are accepted too. */
    internal fun toStandardBase64(s: String): String {
        val t = s.replace('-', '+').replace('_', '/').filterNot { it.isWhitespace() }
        return t + "=".repeat((4 - t.length % 4) % 4)
    }

    private fun key(obj: JsonObject, name: String): JsonElement? =
        obj.entrySet().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** A JSON array of strings, or one string with items separated by commas/new lines. */
    private fun list(obj: JsonObject, name: String): List<String> {
        val e = key(obj, name) ?: return emptyList()
        val raw = when {
            e.isJsonArray -> (e as JsonArray).mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            e.isJsonPrimitive -> e.asString.split(',', '\n')
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // ---- storage --------------------------------------------------------------------------

    fun load(): Profile? =
        MmkvManager.decodeSettingsString(PREF)?.takeIf { it.isNotBlank() }?.let { JsonUtil.fromJsonSafe(it, Profile::class.java) }

    /**
     * After a subscription load: stores the profile from [header] when it parses.
     * @return true when the stored profile changed (the rules must be written again)
     */
    fun update(header: String?, decodeBase64: (String) -> String?): Boolean {
        val p = parse(header, decodeBase64) ?: return false
        if (p == load()) return false
        MmkvManager.encodeSettings(PREF, JsonUtil.toJson(p))
        return true
    }

    fun clear() = MmkvManager.encodeSettings(PREF, "")
}
