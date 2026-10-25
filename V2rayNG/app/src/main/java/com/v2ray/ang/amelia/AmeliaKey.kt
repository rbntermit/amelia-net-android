package com.v2ray.ang.amelia

import java.net.URLDecoder

/** Parsing of what the user types or opens: a bare key, amelia://key/<key> or a subscription URL. */
object AmeliaKey {
    private val KEY_REGEX = Regex("^[A-Za-z0-9_-]{4,128}$")

    /**
     * Returns the subscription id from user input, or null when it does not look like a key.
     * Accepts `<key>`, `amelia://key/<key>` and `https://<any host>/sub/<key>`.
     */
    fun normalize(input: String?): String? {
        var s = input?.trim()?.trim('/') ?: return null
        if (s.isEmpty()) return null

        val deepLinkPrefix = "${AmeliaConfig.DEEP_LINK_SCHEME}://${AmeliaConfig.DEEP_LINK_HOST_KEY}/"
        when {
            s.startsWith(deepLinkPrefix, ignoreCase = true) -> s = s.substring(deepLinkPrefix.length)
            s.contains(AmeliaConfig.SUB_PATH) && s.startsWith("http", ignoreCase = true) ->
                s = s.substringAfter(AmeliaConfig.SUB_PATH)
        }
        s = s.substringBefore('?').substringBefore('#').trim('/')
        s = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
        return s.takeIf { KEY_REGEX.matches(it) }
    }

    /** 1.7.0: what an amelia://key/… link does. */
    enum class LinkAction {
        /** No key entered yet: entered as if typed. */
        SUBMIT,
        /** Another key is entered: ask "Заменить ключ на новый?" (default: keep it). */
        ASK,
        /** The key already entered: nothing to do. */
        SAME,
        /** Not a key. */
        INVALID,
    }

    fun linkAction(current: String?, incoming: String?): LinkAction {
        val key = normalize(incoming) ?: return LinkAction.INVALID
        return when {
            current.isNullOrBlank() -> LinkAction.SUBMIT
            current == key -> LinkAction.SAME
            else -> LinkAction.ASK
        }
    }

    fun subscriptionUrl(baseUrl: String, key: String): String =
        baseUrl.trimEnd('/') + AmeliaConfig.SUB_PATH + key
}
