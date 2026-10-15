package com.v2ray.ang.amelia

import com.google.gson.JsonParser
import com.v2ray.ang.amelia.AmeliaProbe.Entry
import com.v2ray.ang.amelia.AmeliaProbe.Reason
import com.v2ray.ang.amelia.AmeliaProbe.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmeliaProbeTest {

    private val hour = 60L * 60 * 1000
    private val mts = "mobile|MTS RUS"
    private val wifi = "wifi|"

    private fun e(host: String, ok: Boolean, ts: Long, net: String = mts) =
        Entry(host, 2079, ok, if (ok) 200 else null, if (ok) AmeliaPingStage.OK else AmeliaPingStage.DATA, ts, net)

    @Test
    fun firstResultOnANetworkSendsOnce() {
        val (s1, r1) = AmeliaProbe.decide(State(), listOf(e("1.1.1.1", true, 0)), mts, 0)
        assertEquals(Reason.NET_CHANGE, r1)
        assertNotNull(AmeliaProbe.shouldSend(s1, mts, 1, Reason.NET_CHANGE))
        val sent = AmeliaProbe.afterSend(s1, mts, 1, Reason.NET_CHANGE)
        // further results on the same network wait for the periodic send
        val (s2, r2) = AmeliaProbe.decide(sent, listOf(e("1.1.1.1", true, 2)), mts, 2)
        assertNull(r2)
        assertNull(AmeliaProbe.shouldSend(s2, mts, 3, Reason.NET_CHANGE))
    }

    @Test
    fun networkChangeTriggersOneSend() {
        val sent = AmeliaProbe.afterSend(
            AmeliaProbe.decide(State(), listOf(e("1.1.1.1", true, 0)), mts, 0).first, mts, 0, Reason.NET_CHANGE,
        )
        val (s, r) = AmeliaProbe.decide(sent, listOf(e("1.1.1.1", false, 2 * hour, wifi)), wifi, 2 * hour)
        assertEquals(Reason.NET_CHANGE, r)
        val entries = AmeliaProbe.shouldSend(s, wifi, 2 * hour, Reason.NET_CHANGE)
        // only results made on the current network are sent
        assertEquals(listOf(wifi), entries?.map { it.net })
    }

    @Test
    fun aServerThatStopsWorkingIsReportedAtMostHourly() {
        var state = AmeliaProbe.afterSend(
            AmeliaProbe.decide(State(), listOf(e("1.1.1.1", true, 0)), mts, 0).first, mts, 0, Reason.NET_CHANGE,
        )
        val (s1, r1) = AmeliaProbe.decide(state, listOf(e("1.1.1.1", false, hour)), mts, hour)
        assertEquals(Reason.REGRESSION, r1)
        state = AmeliaProbe.afterSend(s1, mts, hour + 10 * 60_000, Reason.REGRESSION)
        // works again, breaks again 20 minutes later: within the hour, no new send
        val (s2, _) = AmeliaProbe.decide(state, listOf(e("1.1.1.1", true, hour + 15 * 60_000)), mts, hour + 15 * 60_000)
        val (_, r3) = AmeliaProbe.decide(s2, listOf(e("1.1.1.1", false, hour + 30 * 60_000)), mts, hour + 30 * 60_000)
        assertNull(r3)
        // a server that never worked is not a regression
        val (_, r4) = AmeliaProbe.decide(state, listOf(e("2.2.2.2", false, 3 * hour)), mts, 3 * hour)
        assertNull(r4)
    }

    @Test
    fun periodicSendAtMostEverySixHoursPerNetwork() {
        val s = AmeliaProbe.afterSend(
            AmeliaProbe.decide(State(), listOf(e("1.1.1.1", true, 0)), mts, 0).first, mts, 0, Reason.NET_CHANGE,
        )
        val fresh = AmeliaProbe.decide(s, listOf(e("1.1.1.1", true, 5 * hour)), mts, 5 * hour).first
        assertNull(AmeliaProbe.shouldSend(fresh, mts, 5 * hour, Reason.PERIODIC))
        assertNotNull(AmeliaProbe.shouldSend(fresh, mts, 6 * hour, Reason.PERIODIC))
        // nothing measured in the last 6 hours: nothing to send
        assertNull(AmeliaProbe.shouldSend(fresh, mts, 12 * hour, Reason.PERIODIC))
    }

    @Test
    fun bodyHasNoNamesKeysOrIds() {
        val body = AmeliaProbe.body(
            "iid-1", 1_700_000_000, AmeliaNetwork.Info("mobile", "MTS RUS"), AmeliaNetwork.PATH_DIRECT,
            listOf(e("79.137.192.178", false, 0), e("1.1.1.1", true, 0)),
        )
        val json = JsonParser.parseString(AmeliaProbe.wireGson.toJson(body)).asJsonObject
        assertEquals(setOf("iid", "ts", "app", "net", "op", "path", "servers"), json.keySet())
        val server = json.getAsJsonArray("servers")[0].asJsonObject
        assertEquals(setOf("host", "port", "ok", "ms", "stage"), server.keySet())
        assertTrue(server.get("ms").isJsonNull)
        assertFalse(server.get("ok").asBoolean)
        assertEquals(2079, server.get("port").asInt)
    }
}
