package com.v2ray.ang.amelia

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmeliaReportTest {

    private val locations = listOf(
        AmeliaReport.Location("g1", "Финляндия 1", "1.1.1.1", 443),
        AmeliaReport.Location("g2", "Нидерланды 1", "79.137.192.178", 2079),
        AmeliaReport.Location("g3", "Швеция 2", "2.2.2.2", 443),
        AmeliaReport.Location("g4", "Германия 1", "3.3.3.3", 443),
    )

    private val checks = mapOf(
        "g1" to AmeliaReport.Check("g1", 180, AmeliaPingStage.OK, null),
        "g2" to AmeliaReport.Check("g2", -1, AmeliaPingStage.CONNECT, "tcp connect failed"),
        "g3" to AmeliaReport.Check("g3", 420, AmeliaPingStage.OK, null),
        // g4 did not finish within 30 s
    )

    private fun body(servers: List<AmeliaReport.Server>, comment: String = "") = AmeliaReport.Body(
        key = "SECRETKEY", ts = 1_700_000_000, app = "1.1.0", code = 7, xray = "26.10.10", hy2 = "xray-26.10.10",
        android = "14", device = "Xiaomi 23053RN02Y", tv = false, net = "mobile", op = "MTS RUS", path = "direct",
        active = "Нидерланды 1", bypassRu = true, comment = comment, servers = servers,
    )

    @Test
    fun serversKeepOrderAndMarkMissingResultsAsFailed() {
        val servers = AmeliaReport.servers(locations, checks)
        assertEquals(listOf("Финляндия 1", "Нидерланды 1", "Швеция 2", "Германия 1"), servers.map { it.name })
        assertEquals(listOf(true, false, true, false), servers.map { it.ok })
        assertEquals(AmeliaPingStage.CONNECT, servers[1].stage)
        assertEquals(AmeliaReport.NO_RESULT_ERROR, servers[3].err)
        assertNull(servers[1].ms)
        assertNull(servers[0].err)

        val (working, failing) = AmeliaReport.split(servers)
        assertEquals(listOf("Финляндия 1", "Швеция 2"), working)
        assertEquals(listOf("Нидерланды 1", "Германия 1"), failing)
    }

    @Test
    fun jsonFollowsTheContract() {
        val json = JsonParser.parseString(AmeliaReport.toJson(body(AmeliaReport.servers(locations, checks)))).asJsonObject
        assertEquals(
            setOf(
                "key", "ts", "app", "code", "xray", "hy2", "android", "device", "tv", "net", "op", "path", "active",
                "bypass_ru", "comment", "servers", "check_ms", "events",
            ),
            json.keySet(),
        )
        val failed = json.getAsJsonArray("servers")[1].asJsonObject
        assertEquals(setOf("name", "host", "port", "ok", "ms", "stage", "err"), failed.keySet())
        assertTrue(failed.get("ms").isJsonNull)
        assertEquals("connect", failed.get("stage").asString)
        assertTrue(json.get("bypass_ru").asBoolean)
    }

    @Test
    fun bodyStaysWithin64KbAndCommentWithin500() {
        val many = (1..2000).map {
            AmeliaReport.Server("Локация $it", "10.0.0.$it", 443, false, null, "data", "x".repeat(200))
        }
        val json = AmeliaReport.toJson(body(many, comment = "я".repeat(2000)))
        assertTrue(json.toByteArray().size <= AmeliaNetwork.MAX_BODY_BYTES)
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals(AmeliaReport.MAX_COMMENT, parsed.get("comment").asString.length)
        assertTrue(parsed.getAsJsonArray("servers").size() > 0)
    }

    @Test
    fun answer() {
        assertEquals("R1234", AmeliaReport.parseAnswer("""{"ok":true,"id":"R1234"}"""))
        assertNull(AmeliaReport.parseAnswer("""{"ok":false,"id":"R1"}"""))
        assertNull(AmeliaReport.parseAnswer("""{"ok":true}"""))
        assertNull(AmeliaReport.parseAnswer("<html>"))
        assertNull(AmeliaReport.parseAnswer(null))
    }

    @Test
    fun plainTextForTheBotHasNoKey() {
        val text = AmeliaReport.plainText(body(AmeliaReport.servers(locations, checks), comment = "не грузит"))
        assertFalse("SECRETKEY" in text)
        assertTrue("Нидерланды 1" in text)
        assertTrue("не грузит" in text)
    }

    @Test
    fun stageClassification() {
        assertEquals("ok", AmeliaPingStage.classify(120, null, null))
        // TCP connected, then the request through the server failed: data is cut
        assertEquals("data", AmeliaPingStage.classify(-1, true, "context deadline exceeded"))
        // UDP (hysteria2): only the error text tells
        assertEquals("connect", AmeliaPingStage.classify(-1, null, "timeout: no recent network activity"))
        assertEquals("connect", AmeliaPingStage.classify(-1, null, "dial udp 1.2.3.4:443: connect: network is unreachable"))
        assertEquals("data", AmeliaPingStage.classify(-1, null, "EOF"))
        assertEquals("data", AmeliaPingStage.classify(-1, null, null))
    }
}
