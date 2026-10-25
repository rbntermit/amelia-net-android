package com.v2ray.ang.amelia

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Amelia130Test {

    @Test
    fun crashTextIsScrubbed() {
        val key = "a1b2c3d4e5f60718"
        val text = """
            java.lang.IllegalStateException: fetch https://msk1.ameliajustamelia.com:9443/sub/$key failed
            config vless://3f1e2d4c-1111-2222-3333-444455556666@79.137.192.178:2079?security=reality#NL
            hy2: hysteria2://secret@203.0.113.7:443 trojan://pw@1.2.3.4:443 uuid 3F1E2D4C-1111-2222-3333-444455556666
            dial tcp 10.0.0.5:443 and 188.120.243.206:443 key=$key other=0123456789abcdef
            at com.v2ray.ang.amelia.AmeliaManager.load(AmeliaManager.kt:120)
        """.trimIndent()
        val s = AmeliaCrash.scrub(text, key)
        assertFalse(key in s)
        assertFalse("0123456789abcdef" in s)
        assertFalse("3f1e2d4c" in s.lowercase())
        assertFalse("vless://" in s || "hysteria2://" in s || "trojan://" in s)
        assertFalse("/sub/" in s)
        assertFalse("203.0.113.7" in s || "10.0.0.5" in s || "1.2.3.4" in s)
        // our servers stay: they help to read the report
        assertTrue("188.120.243.206" in s)
        // the stack itself stays readable
        assertTrue("AmeliaManager.kt:120" in s)
        assertTrue("IllegalStateException" in s)
    }

    @Test
    fun atMostFiveCrashReportsAnHour() {
        val now = 10_000_000L
        assertTrue(AmeliaCrash.canSend(emptyList(), now))
        assertTrue(AmeliaCrash.canSend(List(4) { now - it * 60_000L }, now))
        assertFalse(AmeliaCrash.canSend(List(5) { now - it * 60_000L }, now))
        // older than an hour do not count
        assertTrue(AmeliaCrash.canSend(List(5) { now - 3_700_000L }, now))
    }

    @Test
    fun crashBodyFollowsTheContract() {
        val json = JsonParser.parseString(AmeliaProbe.wireGson.toJson(AmeliaCrash.Crash(where = "core_start"))).asJsonObject
        assertEquals(
            setOf("iid", "ts", "app", "code", "xray", "android", "device", "thread", "where", "fatal", "stack"),
            json.keySet(),
        )
    }

    private val v = AmeliaUpdater.AppVersion(
        versionCode = 11, versionName = "1.3.0",
        url = "https://sub.ameliajustamelia.com:9443/app/AmeliaNet_1.3.0_universal.apk",
        sha256 = "a".repeat(64), size = 75_000_000,
        abis = mapOf("arm64-v8a" to AmeliaUpdater.AbiApk("AmeliaNet_1.3.0_arm64-v8a.apk", "b".repeat(64), 32_400_000)),
    )

    @Test
    fun arm64ApkFirstFromTheAddressThatAnswered() {
        val c = AmeliaUpdater.apkChoices(v, listOf("arm64-v8a", "armeabi-v7a"), "https://msk1.ameliajustamelia.com:9443")
        assertEquals("https://msk1.ameliajustamelia.com:9443/app/AmeliaNet_1.3.0_arm64-v8a.apk", c[0].url)
        assertEquals("b".repeat(64), c[0].sha256)
        // the universal one stays as the fallback
        assertEquals(v.url, c[1].url)
        // without a known address: the origin of "url"
        assertEquals(
            "https://sub.ameliajustamelia.com:9443/app/AmeliaNet_1.3.0_arm64-v8a.apk",
            AmeliaUpdater.apkChoices(v, listOf("arm64-v8a"), null)[0].url,
        )
    }

    @Test
    fun universalWhenNotArm64OrAbisMissingOrBad() {
        assertEquals(listOf(v.url), AmeliaUpdater.apkChoices(v, listOf("armeabi-v7a"), null).map { it.url })
        assertEquals(listOf(v.url), AmeliaUpdater.apkChoices(v.copy(abis = null), listOf("arm64-v8a"), null).map { it.url })
        val badName = v.copy(abis = mapOf("arm64-v8a" to AmeliaUpdater.AbiApk("../evil.apk", "b".repeat(64), 1)))
        assertEquals(listOf(v.url), AmeliaUpdater.apkChoices(badName, listOf("arm64-v8a"), null).map { it.url })
        val badSha = v.copy(abis = mapOf("arm64-v8a" to AmeliaUpdater.AbiApk("a.apk", "xyz", 1)))
        assertEquals(listOf(v.url), AmeliaUpdater.apkChoices(badSha, listOf("arm64-v8a"), null).map { it.url })
    }

    @Test
    fun versionJsonWithAbisParses() {
        val json = """{"versionCode": 11, "versionName": "1.3.0",
            "url": "https://sub.ameliajustamelia.com:9443/app/AmeliaNet_1.3.0_universal.apk",
            "sha256": "${"a".repeat(64)}", "size": 75000000, "notes": "x", "minVersionCode": 1,
            "abis": {"arm64-v8a": {"file": "AmeliaNet_1.3.0_arm64-v8a.apk", "sha256": "${"b".repeat(64)}", "size": 32400000}}}"""
        val parsed = AmeliaUpdater.parse(json)!!
        assertEquals(32_400_000L, parsed.abis?.get("arm64-v8a")?.size)
    }

    @Test
    fun readyNotificationOnceMore() {
        assertTrue(AmeliaUpdater.shouldNotify(11, ready = false, lastCode = 10, lastReady = true))
        assertFalse(AmeliaUpdater.shouldNotify(11, ready = false, lastCode = 11, lastReady = false))
        // downloaded on Wi-Fi later: "готово — установить" once
        assertTrue(AmeliaUpdater.shouldNotify(11, ready = true, lastCode = 11, lastReady = false))
        assertFalse(AmeliaUpdater.shouldNotify(11, ready = true, lastCode = 11, lastReady = true))
        assertEquals("31", AmeliaFormat.megabytes(32_400_000))
    }

    @Test
    fun speedTest() {
        // 10 MB in 1.6 s = 50 Mbit/s
        assertEquals(50.0, AmeliaSpeed.mbps(10_000_000, 1_600), 0.01)
        assertEquals("48", AmeliaSpeed.format(48.4))
        assertEquals("3,2", AmeliaSpeed.format(3.21))
        val cfg = AmeliaSpeed.withInbound("""{"outbounds":[{"protocol":"vless","tag":"proxy"}],"inbounds":[]}""", 23456, "u", "p")
        val inbound = JsonParser.parseString(cfg).asJsonObject.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("http", inbound.get("protocol").asString)
        assertEquals(23456, inbound.get("port").asInt)
        assertEquals("127.0.0.1", inbound.get("listen").asString)
    }
}

class Amelia131Test {

    @Test
    fun resumeFromWhereItStopped() {
        assertEquals(AmeliaUpdater.Resume.APPEND, AmeliaUpdater.resumeAction(have = 10_000_000, code = 206))
        // the server ignored Range and sent everything: start over
        assertEquals(AmeliaUpdater.Resume.RESTART, AmeliaUpdater.resumeAction(have = 10_000_000, code = 200))
        assertEquals(AmeliaUpdater.Resume.RESTART, AmeliaUpdater.resumeAction(have = 0, code = 200))
        // "range not satisfiable": the part may already be complete, check its sha256
        assertEquals(AmeliaUpdater.Resume.CHECK_COMPLETE, AmeliaUpdater.resumeAction(have = 32_438_882, code = 416))
        assertEquals(AmeliaUpdater.Resume.FAIL, AmeliaUpdater.resumeAction(have = 0, code = 404))
        assertEquals(AmeliaUpdater.Resume.FAIL, AmeliaUpdater.resumeAction(have = 0, code = 206))
    }

    @Test
    fun filesOfAnOlderVersionAreDropped() {
        assertTrue(AmeliaUpdater.belongsTo("AmeliaNet-12.apk", 12))
        assertTrue(AmeliaUpdater.belongsTo("AmeliaNet-12-b04f29336bca336c.part", 12))
        assertTrue(AmeliaUpdater.belongsTo(".lock", 12))
        assertFalse(AmeliaUpdater.belongsTo("AmeliaNet-11.apk", 12))
        assertFalse(AmeliaUpdater.belongsTo("AmeliaNet-11-b04f29336bca336c.part", 12))
        // 1 and 12 are different versions
        assertFalse(AmeliaUpdater.belongsTo("AmeliaNet-1.apk", 12))
        assertFalse(AmeliaUpdater.belongsTo("AmeliaNet-120.apk", 12))
    }
}
