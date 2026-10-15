package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaHttp.Route
import com.v2ray.ang.amelia.AmeliaSubscriptionClient.Attempt
import com.v2ray.ang.amelia.AmeliaSubscriptionClient.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AmeliaFallbackTest {

    private val msk1 = "https://msk1.ameliajustamelia.com:9443"
    private val msk2 = "https://msk2.ameliajustamelia.com:9443"
    private val com = "https://sub.ameliajustamelia.com:9443"
    private val net = "https://sub.ameliajustamelia.net:9443"
    private val all = listOf(msk1, msk2, com, net)
    private val ok = Attempt.Ok("dmxlc3M6Ly8=", AmeliaSubInfo(title = "Amelia.Net"))

    @Test
    fun russianMirrorsFirst() {
        assertEquals(all, AmeliaConfig.SUB_BASE_URLS)
    }

    @Test
    fun planTunnelFirstThenDirect() {
        val p = AmeliaHttp.plan(listOf(msk1, com), tunnel = true)
        assertEquals(
            listOf(msk1 to Route.TUNNEL, com to Route.TUNNEL, msk1 to Route.DIRECT, com to Route.DIRECT),
            p.map { it.base to it.route },
        )
        // VPN off: only direct
        assertEquals(listOf(Route.DIRECT, Route.DIRECT), AmeliaHttp.plan(listOf(msk1, com), tunnel = false).map { it.route })
    }

    @Test
    fun lastWorkingAddressIsTriedFirst() {
        assertEquals(listOf(com, msk1, msk2, net), AmeliaHttp.orderBases(com, all))
        assertEquals(all, AmeliaHttp.orderBases(null, all))
        assertEquals(all, AmeliaHttp.orderBases("https://evil.example", all))
    }

    @Test
    fun tunnelFailsDirectMirrorWorks() {
        val tried = mutableListOf<Pair<String, Route>>()
        val r = AmeliaSubscriptionClient.fetchWith(AmeliaHttp.plan(all, tunnel = true)) { a ->
            tried += a.base to a.route
            if (a.route == Route.DIRECT && a.base == msk1) ok else Attempt.Failed
        }
        assertEquals(msk1, (r as Result.Ok).baseUrl)
        // every tunnel attempt first, then the first direct one answered
        assertEquals(all.size + 1, tried.size)
        assertEquals(Route.TUNNEL, tried.first().second)
    }

    @Test
    fun foreignAddressesOnlyAfterMirrors() {
        val tried = mutableListOf<String>()
        val r = AmeliaSubscriptionClient.fetchWith(AmeliaHttp.plan(all, tunnel = false)) { a ->
            tried += a.base
            if (a.base == com) ok else Attempt.Failed
        }
        assertEquals(com, (r as Result.Ok).baseUrl)
        assertEquals(listOf(msk1, msk2, com), tried)
    }

    @Test
    fun unknownKeyOnlyWhenNoServerHasIt() {
        val plan = AmeliaHttp.plan(all, tunnel = false)
        assertEquals(Result.UnknownKey, AmeliaSubscriptionClient.fetchWith(plan) { Attempt.NotFound })
        assertEquals(Result.UnknownKey, AmeliaSubscriptionClient.fetchWith(plan) { if (it.base == msk1) Attempt.NotFound else Attempt.Failed })
        // A server that 404s must not hide a key another one knows
        val r = AmeliaSubscriptionClient.fetchWith(plan) { if (it.base == msk1) Attempt.NotFound else ok }
        assertEquals(msk2, (r as Result.Ok).baseUrl)
    }

    @Test
    fun allDownIsNetworkError() {
        assertEquals(Result.NetworkError, AmeliaSubscriptionClient.fetchWith(AmeliaHttp.plan(all, true)) { Attempt.Failed })
    }

    // ---- APK address substitution --------------------------------------------------------

    @Test
    fun apkHostIsReplacedWithTheAnsweringMirror() {
        val url = "https://sub.ameliajustamelia.com:9443/app/AmeliaNet_1.0.5_universal.apk"
        assertEquals(
            "https://msk1.ameliajustamelia.com:9443/app/AmeliaNet_1.0.5_universal.apk",
            AmeliaHttp.rewriteOrigin(url, msk1),
        )
        assertEquals("$msk2/app/a.apk?v=5", AmeliaHttp.rewriteOrigin("https://sub.ameliajustamelia.net/app/a.apk?v=5", msk2))
        // other hosts stay as they are
        val gh = "https://github.com/rbntermit/amelia-net-android/releases/download/v1.0.5/a.apk"
        assertEquals(gh, AmeliaHttp.rewriteOrigin(gh, msk1))
        assertEquals("http://sub.ameliajustamelia.com/a.apk", AmeliaHttp.rewriteOrigin("http://sub.ameliajustamelia.com/a.apk", msk1))
    }

    @Test
    fun apkDownloadPlanStartsWithTheAnsweringMirror() {
        val url = "https://sub.ameliajustamelia.com:9443/app/A.apk"
        val plan = AmeliaUpdater.downloadPlan(url, lastBase = msk2, tunnel = true)
        assertEquals("$msk2/app/A.apk" to Route.TUNNEL, plan.first())
        assertEquals(all.size * 2, plan.size)
        assertEquals("$msk2/app/A.apk" to Route.DIRECT, plan[all.size])
        assertEquals(listOf("$msk2/app/A.apk", "$msk1/app/A.apk", "$com/app/A.apk", "$net/app/A.apk"),
            plan.take(all.size).map { it.first })
        // a GitHub URL is used as given, tunnel then direct
        val gh = "https://github.com/o/r/releases/download/v1/a.apk"
        assertEquals(listOf(gh to Route.TUNNEL, gh to Route.DIRECT), AmeliaUpdater.downloadPlan(gh, msk1, tunnel = true))
        assertEquals(listOf(gh to Route.DIRECT), AmeliaUpdater.downloadPlan(gh, msk1, tunnel = false))
    }
}
