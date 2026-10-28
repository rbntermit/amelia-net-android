package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.amelia.AmeliaHttp.Route
import com.v2ray.ang.amelia.AmeliaQueue.Why
import com.v2ray.ang.amelia.AmeliaSubscriptionClient.Attempt
import com.v2ray.ang.amelia.AmeliaSubscriptionClient.Result
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.VlessFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1.7.3: the third Russian entry (msk3, Kemerovo) and its mirror; all entries in one list;
 * failover goes to the next location of the list; the main screen says what is checked.
 */
class Amelia173Test {

    private val msk1 = "https://msk1.ameliajustamelia.com:9443"
    private val msk2 = "https://msk2.ameliajustamelia.com:9443"
    private val msk3 = "https://msk3.ameliajustamelia.com:9443"
    private val com = "https://sub.ameliajustamelia.com:9443"
    private val net = "https://sub.ameliajustamelia.net:9443"
    private val ok = Attempt.Ok("dmxlc3M6Ly8=", AmeliaSubInfo(title = "Amelia.Net"))

    // ---- 1, 2: one list of entries ------------------------------------------------------------

    @Test
    fun entriesAreListedOnceWithNameDomainAndAddresses() {
        assertEquals(listOf("msk1", "msk2", "msk3"), AmeliaConfig.ENTRIES.map { it.name })
        val e3 = AmeliaConfig.entry("msk3")
        assertEquals("msk3.ameliajustamelia.com", e3.domain)
        assertEquals("138.124.77.193", e3.ipv4)
        assertEquals("2a05:541:142:1aa::1", e3.ipv6)
        assertEquals(msk3, e3.baseUrl)
        assertEquals("79.137.192.178", AmeliaConfig.entry("msk1").ipv4)
        assertEquals("188.120.243.206", AmeliaConfig.entry("msk2").ipv4)
        assertEquals("msk3", AmeliaConfig.entryNameOf(msk3))
        assertEquals("msk3", AmeliaConfig.entryNameOf("$msk3/"))
        assertNull(AmeliaConfig.entryNameOf(com))
        assertNull(AmeliaConfig.entryNameOf(null))
    }

    @Test
    fun mirrorOrderMsk3AfterMsk2BeforeForeign() {
        assertEquals(listOf(msk1, msk2, msk3, com, net), AmeliaConfig.SUB_BASE_URLS)
        // report, probe, crash, hints: the same Russian order, then the main domain (contract)
        assertEquals(listOf(msk1, msk2, msk3, com), AmeliaNetwork.POST_BASE_URLS)
        // site and its one-time links; the download when nothing works
        assertEquals(listOf(msk2, msk1, msk3), AmeliaConfig.SITE_BASE_URLS)
        assertEquals(listOf(msk2, msk1, msk3), AmeliaSite.BASES)
        assertEquals(AmeliaConfig.SITE_BASE_URLS, AmeliaConfig.RESUBSCRIBE_BASE_URLS)
        // last answering address first, then the list order
        assertEquals(listOf(msk3, msk1, msk2, com, net), AmeliaHttp.orderBases(msk3))
        // msk3 is ours: version.json may point there, and an APK URL is moved to it
        assertTrue(AmeliaHttp.isOwnUrl("$msk3/app/a.apk"))
        assertTrue(AmeliaUpdater.isAllowedUrl("https://msk3.ameliajustamelia.net:9443/app/a.apk"))
        assertEquals("$msk3/app/a.apk", AmeliaHttp.rewriteOrigin("$com/app/a.apk", msk3))
    }

    @Test
    fun noOtherSourceFileWritesAnEntryAddress() {
        val written = listOf(
            "msk1.", "msk2.", "msk3.", "79.137.192.178", "188.120.243.206", "138.124.77.193", "2a05:541:142:1aa",
            "sub.ameliajustamelia.com:", "sub.ameliajustamelia.net:", "91.184.241.246",
        )
        val offenders = mainSources().filter { it.name != "AmeliaConfig.kt" }.flatMap { f ->
            f.readLines().filter { line ->
                val code = line.trim()
                !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") && written.any { it in code }
            }.map { "${f.name}: ${it.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    // ---- 6: a mirror that does not answer, or answers 404, is passed over quietly -----------------

    @Test
    fun deadOr404Msk3MovesOnToTheNextAddress() {
        val all = AmeliaConfig.SUB_BASE_URLS
        val tried = mutableListOf<String>()
        val r = AmeliaSubscriptionClient.fetchWith(AmeliaHttp.plan(all, tunnel = false)) { a ->
            tried += a.base
            when (a.base) {
                msk3 -> Attempt.NotFound
                com -> ok
                else -> Attempt.Failed
            }
        }
        assertEquals(com, (r as Result.Ok).baseUrl)
        assertEquals(listOf(msk1, msk2, msk3, com), tried)
        // msk3 answers when msk1 and msk2 do not
        val r3 = AmeliaSubscriptionClient.fetchWith(AmeliaHttp.plan(all, tunnel = true)) { a ->
            if (a.base == msk3 && a.route == Route.TUNNEL) ok else Attempt.Failed
        }
        assertEquals(msk3, (r3 as Result.Ok).baseUrl)
        // the resubscribe and site lists pass msk3 over the same way
        val rr = AmeliaSubscriptionClient.fetchWith(AmeliaHttp.plan(AmeliaConfig.RESUBSCRIBE_BASE_URLS, tunnel = false)) { a ->
            if (a.base == msk3) Attempt.NotFound else Attempt.Failed
        }
        assertEquals(Result.UnknownKey, rr)
    }

    @Test
    fun journalSaysWhichAddressServedTheSubscription() {
        val str = strings()
        assertEquals("Подписка скачана: msk3, через VPN", AmeliaAbout.subSourceLine(msk3, true, str))
        assertEquals("Подписка скачана: msk1, напрямую", AmeliaAbout.subSourceLine(msk1, false, str))
        assertEquals("Подписка скачана: msk2, напрямую", AmeliaAbout.subSourceLine(msk2, false, str))
        assertEquals("Подписка скачана: запасной (sub.ameliajustamelia.com), напрямую", AmeliaAbout.subSourceLine(com, false, str))
        assertEquals("msk3", AmeliaAbout.baseLabel(msk3, "запасной"))
    }

    @Test
    fun siteOpensTheFirstAnsweringMirrorInItsOrder() {
        assertEquals(msk2, AmeliaSite.pickBase(listOf(true, true, true)))
        assertEquals(msk1, AmeliaSite.pickBase(listOf(false, true, true)))
        assertEquals(msk3, AmeliaSite.pickBase(listOf(false, false, true)))
        // nobody answered: the spare one, as before msk3
        assertEquals(msk1, AmeliaSite.pickBase(listOf(false, false, false)))
        assertEquals(msk1, AmeliaSite.pickBase(emptyList()))
        assertEquals(5_000L, AmeliaSite.CHECK_TIMEOUT_MS)
        assertEquals(5_000L, AmeliaSite.TOKEN_TIMEOUT_MS)
    }

    // ---- 3: our servers stay in a crash report, other addresses go ------------------------------

    @Test
    fun crashKeepsOurMsk3AndRemovesOtherAddresses() {
        assertTrue("138.124.77.193" in AmeliaConfig.OUR_SERVER_IPS)
        assertTrue("2a05:541:142:1aa::1" in AmeliaConfig.OUR_SERVER_IPS)
        val text = """
            dial tcp 138.124.77.193:8471 failed; dial udp [2a05:541:142:1aa::1]:8444 failed
            also 2A05:0541:0142:01AA:0:0:0:1 and 79.137.192.178, 188.120.243.206
            from 95.24.11.7 via [2a02:6b8::feed:1]:443 and 2a05:541:142:1aa::2
        """.trimIndent()
        val s = AmeliaCrash.scrub(text)
        listOf("138.124.77.193", "2a05:541:142:1aa::1", "2A05:0541:0142:01AA:0:0:0:1", "79.137.192.178", "188.120.243.206").forEach {
            assertTrue(it in s, "$it must stay")
        }
        listOf("95.24.11.7", "2a02:6b8::feed:1", "2a05:541:142:1aa::2").forEach { assertFalse(it in s, "$it must go") }
        assertTrue(s.contains("<ip>"))
        assertTrue(s.contains("<ip6>"))
    }

    @Test
    fun ipv6KeyIsOneSpellingOfAnAddress() {
        val k = "2a05:541:142:1aa:0:0:0:1"
        assertEquals(k, AmeliaCrash.ipv6Key("2a05:541:142:1aa::1"))
        assertEquals(k, AmeliaCrash.ipv6Key("2A05:0541:0142:01AA:0000:0000:0000:0001"))
        assertEquals(k, AmeliaCrash.ipv6Key("2a05:541:142:1aa::1%wlan0"))
        assertNull(AmeliaCrash.ipv6Key("12:34:56"))
        assertNull(AmeliaCrash.ipv6Key("1::2::3"))
        assertNull(AmeliaCrash.ipv6Key("1:2:3:4:5:6:7:8:9"))
        assertNull(AmeliaCrash.ipv6Key("zz::1:2"))
        assertTrue(AmeliaCrash.isOurIpv6("2a05:541:142:1aa::1"))
        assertFalse(AmeliaCrash.isOurIpv6("2a05:541:142:1aa::2"))
    }

    // ---- 4: names "… 3" behave like "… 1" and "… 2" ------------------------------------------

    @Test
    fun namesWithThreeAreRecognisedLikeTheOthers() {
        assertTrue(AmeliaReserve.isReserve("🆘 Резервный 3 — только Telegram"))
        assertEquals(AmeliaReserve.isReserve("🆘 Резервный 2 — только Telegram"), AmeliaReserve.isReserve("🆘 Резервный 3 — только Telegram"))
        assertEquals(AmeliaReserve.TEST_URL, AmeliaReserve.testUrl("🆘 Резервный 3 — только Telegram", "x"))
        assertTrue(AmeliaPolicy.isLastResort("🆘 Резервный 3 — только Telegram"))
        for (n in listOf("Швеция 3", "Нидерланды 3", "Финляндия 3", "Казахстан 3")) {
            assertFalse(AmeliaReserve.isReserve(n), n)
            assertFalse(AmeliaReserve.isRussianExit(n), n)
            assertFalse(AmeliaPolicy.isLastResort(n), n)
        }
        // automatic choice never starts on the reserve one, whatever its number
        val locs = listOf(
            AmeliaManager.Location("r3", "🆘 Резервный 3 — только Telegram"),
            AmeliaManager.Location("k3", "Казахстан 3"),
        )
        assertEquals("k3", AmeliaReserve.firstChoice(locs)?.guid)
        // the queue treats "Резервный 3" like "Резервный 2": last, after everything else
        val q = AmeliaQueue.order(
            listOf(loc("r3", "🆘 Резервный 3 — только Telegram"), loc("f3", "Финляндия 3"), loc("r2", "🆘 Резервный 2 — только Telegram")),
            emptyMap(), emptySet(), null, allowLastResort = true,
        )
        assertEquals("f3", q.first())
        // checks and hints are by full name and host:port: "Финляндия 3" is not "Финляндия 1"
        val known = mapOf("Финляндия 1" to AmeliaChecks.Entry("Финляндия 1", ms = -1, ts = now, net = "n"))
        val plan = AmeliaQueue.failoverPlan(
            listOf(loc("f1", "Финляндия 1", "79.137.192.178:8444"), loc("f3", "Финляндия 3", "138.124.77.193:8444")),
            null, emptySet(), emptySet(), known, emptySet(), allowLastResort = false,
        )
        assertEquals(listOf("f3" to Why.LIST, "f1" to Why.PAST_FAIL), plan.steps.map { it.guid to it.why })
    }

    // ---- 5: hysteria2 and VLESS links from the msk3 address ------------------------------------

    @Test
    fun hysteria2LinkFromMsk3() {
        val link = "hy2://p4ssw0rd@138.124.77.193:8454/?sni=msk3.ameliajustamelia.com" +
            "&obfs=salamander&obfs-password=obfsSecret#%F0%9F%87%B0%F0%9F%87%BF%20%D0%9A%D0%B0%D0%B7%D0%B0%D1%85%D1%81%D1%82%D0%B0%D0%BD%203"
        val p = Hysteria2Fmt.parse(link)
        assertEquals(EConfigType.HYSTERIA2, p.configType)
        assertEquals("138.124.77.193", p.server)
        assertEquals("8454", p.serverPort)
        assertEquals("msk3.ameliajustamelia.com", p.sni)
        assertEquals("obfsSecret", p.obfsPassword)
        assertEquals(AppConfig.TLS, p.security)
        assertEquals("🇰🇿 Казахстан 3", p.remarks)
        // turned into Xray settings the same way as the msk1 link (AmeliaLinksTest): salamander over UDP
        p.network = NetworkType.HYSTERIA.type
        val stream = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTransportSettings(stream, p)
        assertEquals(2, stream.hysteriaSettings?.version)
        val mask = stream.finalmask as OutboundBean.StreamSettingsBean.FinalMaskBean
        assertEquals("salamander", mask.udp?.firstOrNull()?.type)
        assertEquals("obfsSecret", mask.udp?.firstOrNull()?.settings?.password)
        assertEquals("msk3.ameliajustamelia.com", stream.tlsSettings?.serverName ?: p.sni)
        assertFalse(AmeliaReserve.isReserve(p.remarks))
        // the same parser takes any SNI: nothing expects msk1 or msk2
        val p1 = Hysteria2Fmt.parse(link.replace("msk3.", "msk1."))
        assertEquals("msk1.ameliajustamelia.com", p1.sni)
    }

    @Test
    fun vlessXhttpRealityFromMsk3() {
        val link = "vless://11111111-2222-3333-4444-555555555555@138.124.77.193:8471" +
            "?security=reality&sni=www.example.com&fp=chrome&pbk=PUBLICKEY&sid=ab12&type=xhttp&mode=auto&path=%2Fx" +
            "#%F0%9F%87%B8%F0%9F%87%AA%20%D0%A8%D0%B2%D0%B5%D1%86%D0%B8%D1%8F%203"
        val p = VlessFmt.parse(link)
        assertNotNull(p)
        assertEquals("138.124.77.193", p!!.server)
        assertEquals("8471", p.serverPort)
        assertEquals(AppConfig.REALITY, p.security)
        assertEquals(NetworkType.XHTTP.type, p.network)
        assertEquals("🇸🇪 Швеция 3", p.remarks)
    }

    // ---- 8: failover goes to the next one in the list --------------------------------------------

    private val now = 1_800_000_000_000L
    private fun loc(guid: String, name: String, hostPort: String = "$guid:443") = AmeliaQueue.Location(guid, name, hostPort)

    /** The list from the 1.7.2 report: "Казахстан 2" and "Казахстан 1" first, "Запасной 🇸🇪" further down. */
    private val list = listOf(
        loc("k2", "Казахстан 2"),
        loc("k1", "Казахстан 1"),
        loc("r2", "🆘 Резервный 2 — только Telegram"),
        loc("se1", "Швеция 1"),
        loc("spare", "Запасной 🇸🇪"),
        loc("r3", "🆘 Резервный 3 — только Telegram"),
        loc("k3", "Казахстан 3"),
    )

    private fun ok(name: String, ms: Long) = name to AmeliaChecks.Entry(name, ms = ms, ts = now, net = "n")

    @Test
    fun nextInTheListNotTheFastest() {
        // "Запасной 🇸🇪" answered fast lately, "Казахстан 1" was never checked: the next one still wins
        val known = mapOf(ok("Запасной 🇸🇪", 40))
        val plan = AmeliaQueue.failoverPlan(list, "Казахстан 2", setOf("Казахстан 2"), emptySet(), known, emptySet(), allowLastResort = false)
        assertEquals(listOf("k1", "se1", "spare", "k3"), plan.steps.map { it.guid })
        assertEquals(Why.LIST, plan.steps.first().why)
        // the old queue would have jumped to "Запасной 🇸🇪" (working by ping first)
        assertEquals("spare", AmeliaQueue.order(list, known, emptySet(), null, exclude = setOf("k2"), allowLastResort = false).first())
    }

    @Test
    fun reserveIsSkippedAndTriedOnesAreLeftOut() {
        val plan = AmeliaQueue.failoverPlan(list, "Казахстан 1", setOf("Казахстан 2", "Казахстан 1"), emptySet(), emptyMap(), emptySet(), false)
        assertEquals(listOf("se1", "spare", "k3"), plan.steps.map { it.guid })
        assertEquals(listOf("r2" to Why.SKIP_RESERVE, "r3" to Why.SKIP_RESERVE), plan.skipped.map { it.guid to it.why })
        // "Запасной 🇸🇪" comes when its turn comes: after "Швеция 1" did not answer
        val after = AmeliaQueue.failoverPlan(list, "Швеция 1", setOf("Казахстан 2", "Казахстан 1", "Швеция 1"), emptySet(), emptyMap(), emptySet(), false)
        assertEquals("spare", after.steps.first().guid)
        // everything tried: nothing left, reserve still not taken while the subscription is valid
        val none = AmeliaQueue.failoverPlan(list, "Казахстан 3", list.map { it.name }.filter { !AmeliaReserve.isReserve(it) }.toSet(), emptySet(), emptyMap(), emptySet(), false)
        assertTrue(none.steps.isEmpty())
    }

    @Test
    fun wrapsAroundFromTheFailedOne() {
        val plan = AmeliaQueue.failoverPlan(list, "Запасной 🇸🇪", setOf("Запасной 🇸🇪"), emptySet(), emptyMap(), emptySet(), false)
        assertEquals(listOf("k3", "k2", "k1", "se1"), plan.steps.map { it.guid })
        // unknown current (renamed by a new subscription): from the top
        val top = AmeliaQueue.failoverPlan(list, "Польша 1", emptySet(), emptySet(), emptyMap(), emptySet(), false)
        assertEquals(listOf("k2", "k1", "se1", "spare", "k3"), top.steps.map { it.guid })
        // over the limit the failed one may be tried again: it comes last, not first
        val again = AmeliaQueue.failoverPlan(list, "Казахстан 2", emptySet(), emptySet(), emptyMap(), emptySet(), false)
        assertEquals("k2", again.steps.last().guid)
    }

    @Test
    fun hintsAndPastFailuresMoveToTheEndKeepingTheirOrder() {
        val bad = setOf("k1:443", "se1:443")
        val known = mapOf("Запасной 🇸🇪" to AmeliaChecks.Entry("Запасной 🇸🇪", ms = -1, ts = now, net = "n"), ok("Швеция 1", 90))
        val plan = AmeliaQueue.failoverPlan(list, "Казахстан 2", setOf("Казахстан 2"), bad, known, setOf("Казахстан 3"), false)
        // "Швеция 1" is cut by the hints but our own check says it works: it keeps its place
        assertEquals(
            listOf("se1" to Why.LIST, "k1" to Why.HINT_BAD, "spare" to Why.PAST_FAIL, "k3" to Why.PAST_FAIL),
            plan.steps.map { it.guid to it.why },
        )
    }

    @Test
    fun afterTheSubscriptionEndedReserveComesLastInItsTurn() {
        val withMoscow = list + loc("msk", "Москва 🇷🇺")
        val plan = AmeliaQueue.failoverPlan(withMoscow, "Казахстан 2", setOf("Казахстан 2"), emptySet(), emptyMap(), emptySet(), allowLastResort = true)
        assertEquals(listOf("k1", "se1", "spare", "k3", "msk", "r2", "r3"), plan.steps.map { it.guid })
        assertTrue(plan.skipped.isEmpty())
        val valid = AmeliaQueue.failoverPlan(withMoscow, "Казахстан 2", setOf("Казахстан 2"), emptySet(), emptyMap(), emptySet(), allowLastResort = false)
        assertEquals(Why.SKIP_RUSSIAN, valid.skipped.single { it.guid == "msk" }.why)
    }

    @Test
    fun theNextFewWithoutAFreshResultAreCheckedFirst() {
        val plan = AmeliaQueue.failoverPlan(list, "Казахстан 2", setOf("Казахстан 2"), emptySet(), emptyMap(), emptySet(), false)
        val fresh = mapOf(ok("Швеция 1", 80))
        assertEquals(listOf("k1", "spare", "k3"), AmeliaQueue.toCheckFirst(plan, fresh))
        assertEquals(AmeliaQuickPick.QUICK_COUNT, AmeliaQueue.toCheckFirst(plan, emptyMap()).size)
    }

    // ---- 9: the journal says why ---------------------------------------------------------------

    @Test
    fun journalExplainsTheOrderAndTheChoice() {
        val str = strings()
        val known = mapOf("Запасной 🇸🇪" to AmeliaChecks.Entry("Запасной 🇸🇪", ms = -1, ts = now, net = "n"))
        val plan = AmeliaQueue.failoverPlan(list, "Казахстан 2", setOf("Казахстан 2"), setOf("se1:443"), known, emptySet(), false)
        assertEquals(
            "Перебор по порядку подписки, начиная со следующей после «Казахстан 2»; " +
                "в конец по подсказкам: у вашего оператора режут «Швеция 1»; " +
                "в конец после недавних неудач: «Запасной 🇸🇪»; " +
                "пропускаю «только Telegram»: «🆘 Резервный 2 — только Telegram», «🆘 Резервный 3 — только Telegram»",
            AmeliaQueue.orderLine(plan, "Казахстан 2", overLimit = false, str),
        )
        assertEquals(
            "«Казахстан 2» не ответил — пробую «Казахстан 1» (следующий по списку)",
            AmeliaQueue.tryLine("Казахстан 2", plan.steps.first(), emptyList(), str),
        )
        assertEquals(
            "«Казахстан 1» не ответил — пробую «Швеция 1» (дальше остались только те, что режут у вашего оператора); " +
                "пропущены — не ответили на проверке сейчас: «Казахстан 3»",
            AmeliaQueue.tryLine("Казахстан 1", plan.steps.first { it.guid == "se1" }, listOf("Казахстан 3"), str),
        )
        val top = AmeliaQueue.orderLine(AmeliaQueue.Plan(emptyList(), emptyList()), null, overLimit = true, str)
        assertEquals("Перебор по порядку подписки, с начала списка; лимит переключений исчерпан — беру только проверенные за последние 2 мин", top)
    }

    @Test
    fun daemonUsesTheListOrderForFailover() {
        val daemon = File(javaDir(), "com/v2ray/ang/amelia/AmeliaDaemon.kt").readText()
        val fast = daemon.substringAfter("private suspend fun switchFast").substringBefore("private fun onMobile")
        assertTrue(fast.contains("AmeliaQueue.failoverNow("))
        assertFalse(fast.contains("AmeliaQueue.current("))
        assertTrue(fast.contains("AmeliaQueue.orderLine("))
        assertTrue(fast.contains("AmeliaQueue.tryLine("))
        assertFalse(daemon.contains("quickPickIfStale"))
        // the screen hears about the check before the warm-up, and who did not answer when switching
        val tick = daemon.substringAfter("private suspend fun tick").substringBefore("var ms = probe(reserve)")
        assertTrue(tick.indexOf("Phase.CHECKING.name") in 0 until tick.indexOf("if (freshTunnel) warmUp()"))
        assertTrue(daemon.contains("Health(Phase.SWITCHING.name, guid = guid, fromName = failedName)"))
    }

    // ---- 7: what the main screen says while checking --------------------------------------------

    private fun ui(phase: ConnPhase, from: String? = null, auto: Boolean = false) = AmeliaUiState(
        phase = phase,
        locations = listOf(AmeliaManager.Location("k1", "Казахстан 1")),
        selectedGuid = "k1",
        switchFrom = from,
        autoSwitch = auto,
    )

    private fun text(s: AmeliaUiState): String? = s.progressText?.let { (id, args) -> strings().get(id, *args.toTypedArray()) }

    @Test
    fun mainScreenSaysWhatIsCheckedAndWhatComesNext() {
        assertEquals(
            "Проверяем интернет через «Казахстан 1»… Если не ответит, приложение само переключится на следующий",
            text(ui(ConnPhase.CHECKING, auto = true)),
        )
        // the person's own choice the app may not leave: no promise to switch
        assertEquals("Проверяем интернет через «Казахстан 1»…", text(ui(ConnPhase.CHECKING, auto = false)))
        assertEquals("«Казахстан 2» не ответил — пробуем «Казахстан 1»", text(ui(ConnPhase.SWITCHING, from = "Казахстан 2")))
        // nothing to name: the plain phase texts
        assertNull(ui(ConnPhase.SWITCHING).progressText)
        assertNull(ui(ConnPhase.SWITCHING, from = "Казахстан 1").progressText)
        assertNull(ui(ConnPhase.CONNECTED, auto = true).progressText)
        assertNull(ui(ConnPhase.CHECKING).copy(selectedGuid = null).progressText)
        // shown as the line under the button (not a pop-up)
        val screen = File(javaDir(), "com/v2ray/ang/amelia/AmeliaScreen.kt").readText()
        assertTrue(screen.substringAfter("private fun PhaseText").contains("state.progressText"))
    }

    @Test
    fun healthCarriesTheNewFieldsWithSafeDefaults() {
        val h = AmeliaDaemon.Health(AmeliaDaemon.Phase.CHECKING.name)
        assertNull(h.fromName)
        assertFalse(h.autoSwitch)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")

    private fun mainSources(): List<File> = javaDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Context.getString in a unit test: the Russian strings file, looked up through R.string's field names. */
    private fun strings(): AmeliaJournal.Strings {
        val xml = File(resDir(), "values/amelia_strings.xml").readText() + File(resDir(), "values/strings.xml").takeIf { it.isFile }?.readText().orEmpty()
        val byName = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&") }
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        return AmeliaJournal.Strings { id, args ->
            val raw = byName[names[id]] ?: error("no string ${names[id]}")
            if (args.isEmpty()) raw else String.format(raw, *args)
        }
    }
}
