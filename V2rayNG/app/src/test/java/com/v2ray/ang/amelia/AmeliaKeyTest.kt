package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AmeliaKeyTest {

    @Test
    fun bareKey() {
        assertEquals("abc123_XY-z", AmeliaKey.normalize("  abc123_XY-z \n"))
    }

    @Test
    fun deepLink() {
        assertEquals("abc123", AmeliaKey.normalize("amelia://key/abc123"))
        assertEquals("abc123", AmeliaKey.normalize("AMELIA://key/abc123/"))
    }

    @Test
    fun subscriptionUrlFromBot() {
        assertEquals("abc123", AmeliaKey.normalize("https://sub.ameliajustamelia.com:9443/sub/abc123"))
        assertEquals("abc123", AmeliaKey.normalize("https://sub.ameliajustamelia.net:9443/sub/abc123?x=1#frag"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(AmeliaKey.normalize(null))
        assertNull(AmeliaKey.normalize(""))
        assertNull(AmeliaKey.normalize("ab"))
        assertNull(AmeliaKey.normalize("vless://uuid@host:443?security=reality"))
        assertNull(AmeliaKey.normalize("has space"))
        assertNull(AmeliaKey.normalize("../../etc"))
        assertNull(AmeliaKey.normalize("https://example.com/other/abc123"))
    }

    @Test
    fun buildsSubscriptionUrl() {
        assertEquals(
            "https://sub.ameliajustamelia.com:9443/sub/abc123",
            AmeliaKey.subscriptionUrl("https://sub.ameliajustamelia.com:9443/", "abc123"),
        )
    }
}
