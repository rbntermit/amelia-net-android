package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaSubscriptionClient.Attempt
import com.v2ray.ang.amelia.AmeliaSubscriptionClient.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AmeliaFallbackTest {

    private val com = "https://sub.ameliajustamelia.com:9443"
    private val net = "https://sub.ameliajustamelia.net:9443"
    private val all = listOf(com, net)
    private val ok = Attempt.Ok("dmxlc3M6Ly8=", AmeliaSubInfo(title = "Amelia.Net"))

    @Test
    fun backupDomainIsConfigured() {
        assertEquals(all, AmeliaConfig.SUB_BASE_URLS)
    }

    @Test
    fun primaryDownSwitchesToBackup() {
        val tried = mutableListOf<String>()
        val r = AmeliaSubscriptionClient.fetchWith(all) { base ->
            tried += base
            if (base == com) Attempt.Failed else ok
        }
        assertEquals(listOf(com, net), tried)
        assertEquals(net, (r as Result.Ok).baseUrl)
    }

    @Test
    fun primaryUpDoesNotTouchBackup() {
        val tried = mutableListOf<String>()
        val r = AmeliaSubscriptionClient.fetchWith(all) { tried += it; ok }
        assertEquals(listOf(com), tried)
        assertEquals(com, (r as Result.Ok).baseUrl)
    }

    @Test
    fun unknownKeyOnlyWhenNoServerHasIt() {
        assertEquals(Result.UnknownKey, AmeliaSubscriptionClient.fetchWith(all) { Attempt.NotFound })
        assertEquals(Result.UnknownKey, AmeliaSubscriptionClient.fetchWith(all) { if (it == com) Attempt.NotFound else Attempt.Failed })
        // A primary that 404s must not hide a key the backup knows
        val r = AmeliaSubscriptionClient.fetchWith(all) { if (it == com) Attempt.NotFound else ok }
        assertEquals(net, (r as Result.Ok).baseUrl)
    }

    @Test
    fun bothDownIsNetworkError() {
        assertEquals(Result.NetworkError, AmeliaSubscriptionClient.fetchWith(all) { Attempt.Failed })
    }

    @Test
    fun lastWorkingServerIsTriedFirst() {
        assertEquals(listOf(net, com), AmeliaSubscriptionClient.orderBases(net, all))
        assertEquals(listOf(com, net), AmeliaSubscriptionClient.orderBases(null, all))
        assertEquals(listOf(com, net), AmeliaSubscriptionClient.orderBases("https://evil.example", all))
    }
}
