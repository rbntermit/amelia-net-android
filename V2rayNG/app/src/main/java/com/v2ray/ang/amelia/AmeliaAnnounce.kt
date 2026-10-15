package com.v2ray.ang.amelia

import com.v2ray.ang.handler.MmkvManager
import java.security.MessageDigest

/**
 * Our announcement from the subscription's `announce` header ("base64:<text>" or plain text,
 * decoded in AmeliaSubInfo), shown as a banner above the locations. Closing it hides this
 * text for good; a different text shows up again.
 */
object AmeliaAnnounce {

    private const val PREF_DISMISSED = "amelia_announce_dismissed"

    data class Link(val start: Int, val end: Int, val url: String)

    private val LINK = Regex("""(?i)\b(https://\S+|(?<![\w.])t\.me/\S+)""")
    private const val TRAILING = ".,;:!?)]}»\"'…"

    /** https://… and t.me/… links in [text] (trailing punctuation is not part of the link). */
    fun links(text: String): List<Link> = LINK.findAll(text).mapNotNull { m ->
        val raw = m.value.trimEnd { it in TRAILING }
        if (raw.length < 6) return@mapNotNull null
        val url = if (raw.startsWith("t.me/", ignoreCase = true)) "https://$raw" else raw
        Link(m.range.first, m.range.first + raw.length, url)
    }.toList()

    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /** The text to show, or null when there is none or the person closed this one. */
    fun visible(text: String?, dismissed: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return t.takeIf { fingerprint(it) != dismissed }
    }

    fun dismissed(): String? = MmkvManager.decodeSettingsString(PREF_DISMISSED)

    fun dismiss(text: String) = MmkvManager.encodeSettings(PREF_DISMISSED, fingerprint(text))
}
