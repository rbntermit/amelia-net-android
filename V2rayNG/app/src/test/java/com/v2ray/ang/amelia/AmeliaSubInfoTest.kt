package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class AmeliaSubInfoTest {

    private val decode: (String) -> String? = { runCatching { String(Base64.getDecoder().decode(it)) }.getOrNull() }

    private fun b64(s: String) = "base64:" + Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun parsesHeadersLikeSubServer() {
        // The same header set sub_server.py sends on /sub/<id>
        val headers = mapOf(
            "profile-title" to b64("Amelia.Net"),
            "subscription-userinfo" to "upload=1073741824; download=2147483648; total=53687091200; expire=1793000000",
            "announce" to b64("⚠️ Подписка кончилась? Локация «🆘 Резервный» внизу"),
            "profile-update-interval" to "12",
        )
        val info = AmeliaSubInfo.fromHeaders({ headers[it.lowercase()] }, now = 42L, decodeBase64 = decode)

        assertEquals("Amelia.Net", info.title)
        assertEquals("⚠️ Подписка кончилась? Локация «🆘 Резервный» внизу", info.announce)
        assertEquals(3L * 1024 * 1024 * 1024, info.used)
        assertEquals(47L * 1024 * 1024 * 1024, info.remaining)
        assertEquals(1793000000L, info.expire)
        assertEquals(12, info.updateIntervalHours)
        assertEquals(42L, info.fetchedAt)
        assertTrue(info.hasLimit)
        assertTrue(info.hasExpire)
    }

    @Test
    fun unlimitedAndMissing() {
        val info = AmeliaSubInfo.fromHeaders(
            { if (it == AmeliaSubInfo.HEADER_USERINFO) "upload=0; download=5; total=0; expire=0" else null },
            now = 0L,
            decodeBase64 = decode,
        )
        assertNull(info.title)
        assertNull(info.announce)
        assertNull(info.remaining)
        assertFalse(info.hasLimit)
        assertFalse(info.hasExpire)
        assertNull(info.updateIntervalHours)
    }

    @Test
    fun plainTextAndBrokenValues() {
        assertEquals("Plain", AmeliaSubInfo.decodeHeaderValue(" Plain ", decode))
        assertNull(AmeliaSubInfo.decodeHeaderValue("base64:%%%", decode))
        assertNull(AmeliaSubInfo.decodeHeaderValue("   ", decode))
        assertEquals(
            mapOf("upload" to 10L, "total" to 20L),
            AmeliaSubInfo.parseUserinfo("upload=10; junk; download=abc; total=2e1"),
        )
    }
}
