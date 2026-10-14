package com.v2ray.ang.amelia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmeliaUpdaterTest {

    private val sha = "a".repeat(64)

    @Test
    fun parsesValidVersion() {
        val v = AmeliaUpdater.parse(
            """{"versionCode":2,"versionName":"1.0.1",
               "url":"https://sub.ameliajustamelia.com:9443/app/AmeliaNet_1.0.1_universal.apk",
               "sha256":"$sha","size":123,"notes":"fixes"}"""
        )
        assertEquals(2, v?.versionCode)
        assertEquals("1.0.1", v?.versionName)
        assertEquals("fixes", v?.notes)
    }

    @Test
    fun rejectsUnsafeOrBroken() {
        assertNull(AmeliaUpdater.parse(null))
        assertNull(AmeliaUpdater.parse("not json"))
        assertNull(AmeliaUpdater.parse("""{"versionCode":0,"url":"https://sub.ameliajustamelia.com/a.apk","sha256":"$sha"}"""))
        assertNull(AmeliaUpdater.parse("""{"versionCode":2,"url":"http://sub.ameliajustamelia.com/a.apk","sha256":"$sha"}"""))
        assertNull(AmeliaUpdater.parse("""{"versionCode":2,"url":"https://evil.example/a.apk","sha256":"$sha"}"""))
        assertNull(AmeliaUpdater.parse("""{"versionCode":2,"url":"https://sub.ameliajustamelia.com/a.apk","sha256":"short"}"""))
        assertNull(AmeliaUpdater.parse("""{"versionCode":2,"url":"https://sub.ameliajustamelia.com/a.apk"}"""))
    }

    private fun json(code: Int, min: Int?) =
        """{"versionCode":$code,"versionName":"x","url":"https://sub.ameliajustamelia.com:9443/app/a.apk","sha256":"$sha"""" +
            (min?.let { ""","minVersionCode":$it""" } ?: "") + "}"

    @Test
    fun minVersionCodeIsOptional() {
        // version.json from before 1.0.1 has no minVersionCode
        assertNull(AmeliaUpdater.parse(json(2, null))?.minVersionCode)
        assertEquals(1, AmeliaUpdater.parse(json(2, 1))?.minVersionCode)
        // nonsense values are dropped instead of locking users out
        assertNull(AmeliaUpdater.parse(json(2, 0))?.minVersionCode)
        assertNull(AmeliaUpdater.parse(json(2, 5))?.minVersionCode)
    }

    @Test
    fun requiredOnlyBelowMinVersion() {
        val v = AmeliaUpdater.parse(json(5, 3))
        assertTrue(AmeliaUpdater.isRequired(v, installed = 2))
        assertFalse(AmeliaUpdater.isRequired(v, installed = 3))
        assertFalse(AmeliaUpdater.isRequired(v, installed = 5))
        assertFalse(AmeliaUpdater.isRequired(AmeliaUpdater.parse(json(5, null)), installed = 1))
        assertFalse(AmeliaUpdater.isRequired(null, installed = 1))
    }

    @Test
    fun allowedHosts() {
        assertTrue(AmeliaUpdater.isAllowedUrl("https://github.com/o/r/releases/download/v1/a.apk"))
        assertTrue(AmeliaUpdater.isAllowedUrl("https://release-assets.githubusercontent.com/x"))
        assertTrue(AmeliaUpdater.isAllowedUrl("https://sub.ameliajustamelia.net:9443/app/a.apk"))
        assertFalse(AmeliaUpdater.isAllowedUrl("https://ameliajustamelia.com.evil.example/a.apk"))
        assertFalse(AmeliaUpdater.isAllowedUrl("https://notameliajustamelia.com/a.apk"))
        assertFalse(AmeliaUpdater.isAllowedUrl("ftp://github.com/a.apk"))
    }
}
