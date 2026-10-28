package com.v2ray.ang.amelia

import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreLifecycleRules
import com.v2ray.ang.core.CoreLifecycleRules.StartCommand
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import kotlin.random.Random

/** 1.7.0: fixes from the 1.6.1 code review (numbers in the names are the task's items). */
class Amelia170Test {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    // ---- A1: nobody else can stop the VPN or draw on the widget ------------------------------

    @Test
    fun a1_receiversAreNeverExported() {
        // all processes share one UID: our own messages still arrive, other apps' do not
        assertEquals(ContextCompat.RECEIVER_NOT_EXPORTED, Utils.receiverFlags())
        val offenders = mainSources().filter { it.readText().contains("RECEIVER_EXPORTED") && !it.readText().contains("RECEIVER_NOT_EXPORTED") }
        assertTrue(offenders.isEmpty(), "RECEIVER_EXPORTED in $offenders")
        val utils = File(javaDir(), "com/v2ray/ang/util/Utils.kt").readText()
        assertFalse(Regex("""=\s*ContextCompat\.RECEIVER_EXPORTED""").containsMatchIn(utils))
    }

    @Test
    fun a1_widgetTakesStateOnlyAsAnExplicitIntent() {
        val manifest = manifest()
        val widget = manifest.substringAfter("android:name=\".receiver.WidgetProvider\"").substringBefore("</receiver>")
        assertTrue(widget.contains("android:exported=\"false\""))
        assertFalse(widget.contains(".action.activity"))
        assertTrue(widget.contains("android.appwidget.action.APPWIDGET_UPDATE"))
        // the state messages it redraws on are forwarded to it, the rest (traffic every few seconds) is not
        assertTrue(AmeliaWidget.forwards(AppConfig.MSG_STATE_START_SUCCESS))
        assertTrue(AmeliaWidget.forwards(AppConfig.MSG_STATE_STOP_SUCCESS))
        assertTrue(AmeliaWidget.forwards(AmeliaConfig.MSG_HEALTH))
        assertFalse(AmeliaWidget.forwards(AmeliaConfig.MSG_TRAFFIC))
        assertFalse(AmeliaWidget.forwards(AppConfig.MSG_STATE_STOP))
    }

    @Test
    fun a1_onlyTheseComponentsAreExported() {
        val manifest = manifest()
        val exported = Regex("""<(activity|service|receiver|provider)\s[^>]*?android:name="([^"]+)"[^>]*?android:exported="true"""")
            .findAll(manifest).map { it.groupValues[2] }.toSet() +
            Regex("""<(activity|service|receiver|provider)\s[^>]*?android:exported="true"[^>]*?android:name="([^"]+)"""")
                .findAll(manifest).map { it.groupValues[2] }.toSet()
        // the launcher screen, boot and update broadcasts of the system, the tile (system-bound with a permission)
        assertEquals(
            setOf(".amelia.AmeliaActivity", ".receiver.BootReceiver", ".amelia.AmeliaInstaller\$PackageReplacedReceiver", ".service.QSTileService"),
            exported,
        )
        val tile = manifest.substringAfter("android:name=\".service.QSTileService\"").substringBefore("</service>")
        assertTrue(tile.contains("android.permission.BIND_QUICK_SETTINGS_TILE"))
    }

    // ---- A2: the local proxy has a login and its own port --------------------------------------

    @Test
    fun a2_anUpdateFromOldSettingsGetsANewLoginAndPort() {
        // 1.6.1: port 10808, no login
        val v = AmeliaLocalProxy.plan(done = false, port = "10808", user = null, pass = null, random = Random(1))
        assertNotNull(v)
        v!!
        assertTrue(v.port in AmeliaLocalProxy.PORT_MIN..AmeliaLocalProxy.PORT_MAX)
        assertTrue(v.user.length >= 12 && v.pass.length >= 24)
        assertTrue(v.user.all { it.isLetterOrDigit() } && v.pass.all { it.isLetterOrDigit() })
        // even a login typed into the old upstream settings is replaced once
        val old = AmeliaLocalProxy.plan(done = false, port = "30000", user = "u", pass = "p", random = Random(2))!!
        assertNotEquals("u", old.user)
    }

    @Test
    fun a2_valuesAreMadeOnceAndKept() {
        assertNull(AmeliaLocalProxy.plan(done = true, port = "34567", user = "abc", pass = "def", random = Random(3)))
        // a lost password is made again, the port stays
        val v = AmeliaLocalProxy.plan(done = true, port = "34567", user = "abc", pass = null, random = Random(4))!!
        assertEquals(34567, v.port)
        assertEquals("abc", v.user)
        assertTrue(v.pass.isNotBlank())
        // a port outside the range is replaced
        assertTrue(AmeliaLocalProxy.plan(done = true, port = "10808", user = "abc", pass = "def", random = Random(5))!!.port >= AmeliaLocalProxy.PORT_MIN)
    }

    @Test
    fun a2_portsAndSecrets() {
        val r = Random(6)
        repeat(1000) { assertTrue(AmeliaLocalProxy.newPort(r) in 20_000..60_000) }
        repeat(100) { assertNotEquals(40_000, AmeliaLocalProxy.otherPort(40_000, Random(it))) }
        assertNotEquals(AmeliaLocalProxy.newSecret(Random(7)), AmeliaLocalProxy.newSecret(Random(8)))
    }

    @Test
    fun a2_speedTestInboundAsksForTheLoginToo() {
        val cfg = AmeliaSpeed.withInbound("""{"outbounds":[]}""", 23456, "user1", "pass1")
        val account = JsonParser.parseString(cfg).asJsonObject.getAsJsonArray("inbounds")[0].asJsonObject
            .getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject
        assertEquals("user1", account.get("user").asString)
        assertEquals("pass1", account.get("pass").asString)
    }

    @Test
    fun a2_inboundIsNeverSharedOrOfferedAsSystemProxy() {
        val routing = File(javaDir(), "com/v2ray/ang/amelia/AmeliaRouting.kt").readText()
        assertTrue(routing.contains("encodeSettings(AppConfig.PREF_PROXY_SHARING, false)"))
        assertTrue(routing.contains("encodeSettings(AppConfig.PREF_APPEND_HTTP_PROXY, false)"))
        // the login reaches tun2socks and the core's inbound through the same settings keys
        val hev = File(javaDir(), "com/v2ray/ang/service/TProxyService.kt").readText()
        assertTrue(hev.contains("SettingsManager.getSocksPassword()"))
        assertFalse(hev.contains("Config content"))
    }

    // ---- A3: certificates and backups ----------------------------------------------------------

    @Test
    fun a3_ownDomainsTrustOnlySystemCertificatesAndNoPlainHttp() {
        val nsc = File(resDir(), "xml/network_security_config.xml").readText()
        val own = nsc.substringAfter("<domain-config cleartextTrafficPermitted=\"false\">").substringBefore("</domain-config>")
        assertTrue(own.contains("<domain includeSubdomains=\"true\">ameliajustamelia.com</domain>"))
        assertTrue(own.contains("<domain includeSubdomains=\"true\">ameliajustamelia.net</domain>"))
        assertTrue(own.contains("<certificates src=\"system\" />"))
        assertFalse(own.contains("src=\"user\""))
        // no pinning (certificates are reissued) and plain HTTP nowhere
        assertFalse(nsc.contains("<pin-set"))
        assertFalse(nsc.contains("cleartextTrafficPermitted=\"true\""))
        assertFalse(manifest().contains("usesCleartextTraffic=\"true\""))
    }

    @Test
    fun a5_keyAndSubscriptionStayOutOfAndroidBackups() {
        val manifest = manifest()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        val full = File(resDir(), "xml/backup_rules.xml").readText()
        val modern = File(resDir(), "xml/data_extraction_rules.xml").readText()
        for (path in listOf("mmkv/", "crashes/", "hev-socks5-tunnel.yaml")) {
            assertTrue(full.contains("<exclude domain=\"file\" path=\"$path\" />"), path)
            // both the cloud backup and moving to a new phone
            assertEquals(2, Regex(Regex.escape("<exclude domain=\"file\" path=\"$path\" />")).findAll(modern).count(), path)
        }
    }

    // ---- A4: amelia://key/… does not replace a key silently -------------------------------------

    @Test
    fun a4_keyLink() {
        assertEquals(AmeliaKey.LinkAction.SUBMIT, AmeliaKey.linkAction(null, "abcd1234"))
        assertEquals(AmeliaKey.LinkAction.SUBMIT, AmeliaKey.linkAction("", "abcd1234"))
        assertEquals(AmeliaKey.LinkAction.SAME, AmeliaKey.linkAction("abcd1234", "abcd1234"))
        assertEquals(AmeliaKey.LinkAction.ASK, AmeliaKey.linkAction("abcd1234", "zzzz9999"))
        assertEquals(AmeliaKey.LinkAction.INVALID, AmeliaKey.linkAction("abcd1234", "no spaces allowed"))
        val strings = File(resDir(), "values/amelia_strings.xml").readText()
        assertTrue(strings.contains("<string name=\"amelia_key_replace_title\">Заменить ключ на новый?</string>"))
    }

    // ---- A5: one-time links to the site -----------------------------------------------------------

    private val base = "https://msk2.ameliajustamelia.com:9443"

    @Test
    fun a5_tokenAnswerGivesLinksOnTheSameSite() {
        val t = AmeliaSite.parseToken(base, """{"ok":true,"t":"t.abc","ttl":600,"me":"/me/t.abc","buy":"/buy?sid=t.abc"}""", now)!!
        assertEquals("$base/me/t.abc", t.cabinetUrl)
        assertEquals("$base/buy?sid=t.abc", t.buyUrl)
        assertEquals(now + 600_000, t.until)
        assertTrue(AmeliaSite.usable(t, now + 5 * min))
        assertFalse(AmeliaSite.usable(t, now + 10 * min))
    }

    @Test
    fun a5_tokenIsNeverKeptLongerThan20Minutes() {
        val t = AmeliaSite.parseToken(base, """{"ok":true,"t":"t.abc","ttl":1800,"me":"/me/t.abc","buy":"/buy?sid=t.abc"}""", now)!!
        assertEquals(now + 20 * min, t.until)
        assertEquals(now + 20 * min, AmeliaSite.parseToken(base, """{"ok":true,"t":"t.a","me":"/me/t.a","buy":"/buy?sid=t.a"}""", now)!!.until)
    }

    @Test
    fun a5_badAnswersFallBackToTheKey() {
        assertNull(AmeliaSite.parseToken(base, null, now))
        assertNull(AmeliaSite.parseToken(base, "not json", now))
        assertNull(AmeliaSite.parseToken(base, """{"ok":false}""", now))
        assertNull(AmeliaSite.parseToken(base, """{"ok":true,"t":"","me":"/me/x","buy":"/buy?sid=x"}""", now))
        // a link to another site is never opened
        assertNull(AmeliaSite.parseToken(base, """{"ok":true,"t":"t.a","me":"https://evil.example/me","buy":"/buy?sid=t.a"}""", now))
        assertNull(AmeliaSite.parseToken(base, """{"ok":true,"t":"t.a","me":"//evil.example/me","buy":"/buy?sid=t.a"}""", now))
        assertFalse(AmeliaSite.usable(null, now))
        assertEquals("""{"key":"abcd1234"}""", AmeliaSite.tokenRequest("abcd1234"))
        assertEquals(listOf(base, "https://msk1.ameliajustamelia.com:9443", "https://msk3.ameliajustamelia.com:9443"), AmeliaConfig.RESUBSCRIBE_BASE_URLS)
        assertEquals(5_000L, AmeliaSite.TOKEN_TIMEOUT_MS)
    }

    // ---- B6, B9, B10: the core's lifecycle -----------------------------------------------------------

    @Test
    fun b6_afterAStopNothingStartsTheCoreAgain() {
        // a location switch or a network change waiting for the lock after "Выключить"
        assertFalse(CoreLifecycleRules.mayReload(stopping = true, running = true, ownMonitor = true))
        assertFalse(CoreLifecycleRules.mayStart(stopping = true, running = false))
        assertFalse(CoreLifecycleRules.mayRevive(started = true, stopping = true, running = false))
        // the usual reload
        assertTrue(CoreLifecycleRules.mayReload(stopping = false, running = true, ownMonitor = true))
        // an old network monitor (unregistered meanwhile) reloads nothing
        assertFalse(CoreLifecycleRules.mayReload(stopping = false, running = true, ownMonitor = false))
        // a core that is not running is not "reloaded" (revive is a separate, explicit step)
        assertFalse(CoreLifecycleRules.mayReload(stopping = false, running = false, ownMonitor = true))
        // a first start, never over a running core
        assertTrue(CoreLifecycleRules.mayStart(stopping = false, running = false))
        assertFalse(CoreLifecycleRules.mayStart(stopping = false, running = true))
    }

    @Test
    fun b9_b10_startCommands() {
        // cold start
        assertEquals(StartCommand.START, CoreLifecycleRules.startCommand(coreRunning = false, startLockFree = true))
        // a second equivalent start while the first sets up: one core, one interface
        assertEquals(StartCommand.IN_PROGRESS, CoreLifecycleRules.startCommand(coreRunning = false, startLockFree = false))
        // after a successful start the lock is free again; the next command keeps the running core (sticky)
        assertEquals(StartCommand.ALREADY_RUNNING, CoreLifecycleRules.startCommand(coreRunning = true, startLockFree = true))
        val vpn = File(javaDir(), "com/v2ray/ang/service/CoreVpnService.kt").readText()
        // foreground first, then the setup off the main thread
        assertTrue(vpn.indexOf("NotificationManager.ensureForeground()") < vpn.indexOf("setupScope.launch"))
        assertTrue(Regex("""finally \{\s+unlockStart\(\)""").containsMatchIn(vpn))
    }

    @Test
    fun b9_serverNamesResolveWithinTheTimeout() {
        val fails = mutableListOf<String>()
        val slow = HttpUtil.lookupWithTimeout("slow.example", 200, lookup = { Thread.sleep(5_000); emptyArray() }) { m, _ -> fails += m }
        assertNull(slow)
        assertEquals(1, fails.size)
        val fast = HttpUtil.lookupWithTimeout("fast.example", 2_000, lookup = { arrayOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))) }) { m, _ -> fails += m }
        assertEquals("1.2.3.4", fast!!.single().hostAddress)
        assertEquals(3_000L, HttpUtil.DNS_TIMEOUT_MS)
    }

    // ---- B7: the daemon brings the core back -----------------------------------------------------------

    @Test
    fun b7_coreDownForTenSecondsIsStartedAgain() {
        assertFalse(CoreLifecycleRules.reviveDue(downSince = 0, now = now))
        assertFalse(CoreLifecycleRules.reviveDue(downSince = now - 9_999, now = now))
        assertTrue(CoreLifecycleRules.reviveDue(downSince = now - 10_000, now = now))
        // only while the service lives
        assertTrue(CoreLifecycleRules.mayRevive(started = true, stopping = false, running = false))
        assertFalse(CoreLifecycleRules.mayRevive(started = false, stopping = false, running = false))
        assertFalse(CoreLifecycleRules.mayRevive(started = true, stopping = false, running = true))
    }

    @Test
    fun b7_restoreAndSwitchFollowTheLocationByName() {
        val daemon = File(javaDir(), "com/v2ray/ang/amelia/AmeliaDaemon.kt").readText()
        assertTrue(daemon.contains("private suspend fun restore(service: Service, name: String, fallback: String?"))
        assertTrue(daemon.contains("guidOf(name, fallback)"))
        assertTrue(daemon.contains("val triedNames = mutableSetOf<String>()"))
    }

    // ---- B8 ---------------------------------------------------------------------------------------------

    @Test
    fun b8_overTheLimitTheFullCheckIsWaitedFor() {
        assertEquals(20_000L, AmeliaDaemon.OVER_LIMIT_FULL_CHECK_MS)
        val daemon = File(javaDir(), "com/v2ray/ang/amelia/AmeliaDaemon.kt").readText()
        val over = daemon.substringAfter("private suspend fun switchOverLimit").substringBefore("private fun stayLimited")
        // full check after the quick ones, before the subscription is downloaded again
        assertTrue(over.indexOf("checkCandidatesNow") < over.indexOf("awaitFullCheck"))
        assertTrue(over.indexOf("awaitFullCheck") < over.indexOf("resubscribeIfStale"))
    }

    // ---- B11: only this network's checks are "fresh" --------------------------------------------------

    private val mobile = "mobile|AS8359"
    private val wifi = "wifi|AS12389"

    private fun entry(name: String, ok: Boolean, ageMs: Long, net: String) =
        AmeliaChecks.Entry(name, "h", 443, if (ok) 100 else -1, if (ok) "ok" else "data", now - ageMs, net)

    @Test
    fun b11_freshResultsAreThisNetworksOnly() {
        val store = AmeliaChecks.Store(
            mapOf(
                "A" to entry("A", true, 1 * min, wifi),
                "B" to entry("B", true, 1 * min, mobile),
                "C" to entry("C", true, 20 * min, mobile),
            ),
        )
        assertEquals(setOf("B"), AmeliaChecks.fresh(store, now, mobile).keys)
        assertEquals(setOf("A"), AmeliaChecks.fresh(store, now, wifi).keys)
        // over the limit: checked on Wi-Fi a minute ago is not "surely working" on mobile
        assertFalse(AmeliaSwitchLimit.verified(entry("A", true, 1 * min, wifi), now, mobile))
        assertTrue(AmeliaSwitchLimit.verified(entry("A", true, 1 * min, mobile), now, mobile))
        val names = mapOf("a" to "A", "b" to "B")
        assertEquals(listOf("b"), AmeliaSwitchLimit.verifiedTargets(listOf("a", "b"), { names[it] }, store.entries, now, mobile))
    }

    // ---- B12: journals and the watch loop -------------------------------------------------------------

    @Test
    fun b12_journalsAreWrittenUnderTheSharedLock() {
        val policy = File(javaDir(), "com/v2ray/ang/amelia/AmeliaPolicy.kt").readText()
        val known = File(javaDir(), "com/v2ray/ang/amelia/AmeliaKnown.kt").readText()
        assertTrue(policy.contains("fun add(text: String, now: Long = System.currentTimeMillis()) = MmkvManager.withSettingsLock {"))
        assertTrue(known.contains("fun add(e: Event) = MmkvManager.withSettingsLock {"))
        val daemon = File(javaDir(), "com/v2ray/ang/amelia/AmeliaDaemon.kt").readText()
        assertTrue(daemon.contains("private fun startWatch(service: Service, firstDelay: Long) = synchronized(watchLock) {"))
        // the order of the journal itself is unchanged
        val list = (1..25).fold(emptyList<AmeliaJournal.Entry>()) { l, i -> AmeliaJournal.append(l, AmeliaJournal.Entry(i.toLong(), "t$i")) }
        assertEquals(AmeliaJournal.MAX, list.size)
        assertEquals("t25", list.last().text)
    }

    // ---- C13: no needless request to ya.ru --------------------------------------------------------------

    @Test
    fun c13_directCheckOnlyWithoutAValidatedNetwork() {
        var asked = 0
        assertFalse(AmeliaPolicy.isOffline(validated = true) { asked++; false })
        assertEquals(0, asked)
        assertTrue(AmeliaPolicy.isOffline(validated = false) { asked++; false })
        assertFalse(AmeliaPolicy.isOffline(validated = false) { asked++; true })
        assertEquals(2, asked)
    }

    // ---- C14: "nothing works" pauses -----------------------------------------------------------------------

    @Test
    fun c14_pausesGrowAndAreLongerWithTheScreenOff() {
        assertEquals(listOf(2L, 5L, 10L, 15L, 15L).map { it * min }, (1..5).map { AmeliaPolicy.allFailedWait(it, screenOn = true) })
        assertEquals(listOf(5L, 10L, 15L, 15L).map { it * min }, (1..4).map { AmeliaPolicy.allFailedWait(it, screenOn = false) })
        // a full check at most every 5 minutes
        assertFalse(AmeliaPolicy.fullCheckDue(lastFullAt = now - 4 * min, now = now))
        assertTrue(AmeliaPolicy.fullCheckDue(lastFullAt = now - 5 * min, now = now))
        assertTrue(AmeliaPolicy.fullCheckDue(lastFullAt = 0, now = now))
        // a clock moved back does not block it forever
        assertTrue(AmeliaPolicy.fullCheckDue(lastFullAt = now + 10 * min, now = now))
    }

    // ---- C15: test cores ------------------------------------------------------------------------------------

    @Test
    fun c15_atMostEightTestCoresAndOneCheckPerLocation() {
        assertEquals(8, AmeliaCheck.MAX_CORES)
        assertEquals(AmeliaCheck.inFlightKey("NL", "1.1.1.1", 443), AmeliaCheck.inFlightKey("NL", "1.1.1.1", 443))
        // a new port after a subscription download is another check
        assertNotEquals(AmeliaCheck.inFlightKey("NL", "1.1.1.1", 443), AmeliaCheck.inFlightKey("NL", "1.1.1.1", 8443))
        // the timeout counts from the start of the core, not from the queue
        assertEquals(3_000L, AmeliaCheck.timeLeft(5_000, startedAt = 1_000, now = 3_000))
        assertEquals(0L, AmeliaCheck.timeLeft(5_000, startedAt = 1_000, now = 9_000))
    }

    // ---- C16: no switching allowed --------------------------------------------------------------------------

    @Test
    fun c16_failedRoundsWithoutSwitchingSlowDown() {
        assertEquals(30_000L, AmeliaPolicy.noSwitchWait(1, screenOn = true))
        assertEquals(120_000L, AmeliaPolicy.noSwitchWait(1, screenOn = false))
        assertEquals(120_000L, AmeliaPolicy.noSwitchWait(3, screenOn = true))
        assertEquals(300_000L, AmeliaPolicy.noSwitchWait(5, screenOn = false))
        assertEquals(AmeliaDaemon.INTERVAL_SCREEN_ON_MS, AmeliaPolicy.INTERVAL_ON_MS)
        assertEquals(AmeliaDaemon.INTERVAL_SCREEN_OFF_MS, AmeliaPolicy.INTERVAL_OFF_MS)
    }

    // ---- C17: widget and tile --------------------------------------------------------------------------------

    @Test
    fun c17_widgetSaysWhenNothingWorks() {
        val s = AmeliaWidget.Status.FAILED
        assertEquals(s, AmeliaWidget.status(true, AmeliaDaemon.Phase.ALL_FAILED.name, 0, now))
        assertEquals(s, AmeliaWidget.status(true, AmeliaDaemon.Phase.MANUAL_FAILED.name, 0, now))
        assertEquals(AmeliaWidget.Status.NO_INTERNET, AmeliaWidget.status(true, AmeliaDaemon.Phase.NO_INTERNET.name, 0, now))
        assertEquals(AmeliaWidget.Status.CONNECTED, AmeliaWidget.status(true, AmeliaDaemon.Phase.CONNECTED.name, 0, now))
        // the VPN is off: no stale "Не работает"
        assertEquals(AmeliaWidget.Status.OFF, AmeliaWidget.status(false, AmeliaDaemon.Phase.ALL_FAILED.name, 0, now))
        assertTrue(AmeliaWidget.isWarning(AmeliaWidget.Status.FAILED))
        assertTrue(AmeliaWidget.isWarning(AmeliaWidget.Status.NO_INTERNET))
        assertFalse(AmeliaWidget.isWarning(AmeliaWidget.Status.CONNECTED))
        val m = AmeliaWidget.model(AmeliaWidget.Status.FAILED, "NL", null, now) { "" }
        assertEquals(R.string.amelia_widget_not_working, AmeliaWidget.lines(m).status)
        // the button still turns the VPN off
        assertEquals(AmeliaWidget.Button.DISCONNECT, m.button)
        assertEquals(R.string.amelia_widget_no_internet, AmeliaWidget.lines(m.copy(status = AmeliaWidget.Status.NO_INTERNET)).status)
    }

    @Test
    fun c17_tileStateLine() {
        assertEquals(R.string.amelia_phase_connected, AmeliaTile.stateText(AmeliaDaemon.Phase.CONNECTED.name))
        assertEquals(R.string.amelia_widget_not_working, AmeliaTile.stateText(AmeliaDaemon.Phase.ALL_FAILED.name))
        assertEquals(R.string.amelia_widget_not_working, AmeliaTile.stateText(AmeliaDaemon.Phase.MANUAL_FAILED.name))
        assertEquals(R.string.amelia_widget_no_internet, AmeliaTile.stateText(AmeliaDaemon.Phase.NO_INTERNET.name))
        assertNull(AmeliaTile.stateText(null))
        assertTrue(AmeliaTile.isProblem(AmeliaDaemon.Phase.NO_INTERNET.name))
        assertFalse(AmeliaTile.isProblem(AmeliaDaemon.Phase.CONNECTED.name))
        val strings = File(resDir(), "values/amelia_strings.xml").readText()
        assertTrue(strings.contains("<string name=\"amelia_widget_not_working\">Не работает</string>"))
        assertTrue(strings.contains("<string name=\"amelia_widget_no_internet\">Нет интернета</string>"))
    }

    // ---- D18: one subscription updater ----------------------------------------------------------------------

    @Test
    fun d18_onlyTheAppsWorkerRefreshesTheSubscription() {
        val h = 3_600_000L
        assertTrue(AmeliaUpdateWorker.subscriptionDue(0, null, now))
        assertFalse(AmeliaUpdateWorker.subscriptionDue(now - 11 * h, null, now))
        assertTrue(AmeliaUpdateWorker.subscriptionDue(now - 12 * h, null, now))
        assertTrue(AmeliaUpdateWorker.subscriptionDue(now - 6 * h, 6, now))
        assertFalse(AmeliaUpdateWorker.subscriptionDue(now - 5 * h, 6, now))
        val manager = File(javaDir(), "com/v2ray/ang/amelia/AmeliaManager.kt").readText()
        assertTrue(manager.contains("autoUpdate = false"))
        assertFalse(manager.contains("autoUpdate = true"))
    }

    // ---- D19: crash reports ------------------------------------------------------------------------------------

    @Test
    fun d19_crashReportsLoseIpv6PasswordsAndLogins() {
        val text = """
            dial tcp [2a02:6b8::feed:1]:443 failed; from fe80::1c2d:3e4f:5a6b:7c8d%wlan0
            java.net.ConnectException: failed to connect to 2001:db8:85a3:0:0:8a2e:370:7334
            proxy http://alice:s3cret@203.0.113.9:8080/ socks5://bob:pw@host:1080
            {"password":"trojanpass","method":"aes-256-gcm"} password: sspass psk=wgkey==
            at 12:34:56 in Foo.kt:120 and AmeliaManager::load
        """.trimIndent()
        val s = AmeliaCrash.scrub(text)
        for (secret in listOf("2a02:6b8", "fe80::1c2d", "2001:db8", "alice", "s3cret", "bob:pw", "trojanpass", "sspass", "wgkey")) {
            assertFalse(secret in s, "$secret in $s")
        }
        // what helps to read the report stays
        assertTrue("12:34:56" in s)
        assertTrue("Foo.kt:120" in s)
        assertTrue("aes-256-gcm" in s)
        assertTrue(AmeliaCrash.isIpv6("2a02:6b8::1"))
        assertFalse(AmeliaCrash.isIpv6("12:34:56"))
        assertFalse(AmeliaCrash.isIpv6("::"))
    }

    // ---- D20, D21, D22 --------------------------------------------------------------------------------------------

    @Test
    fun d20_noLocalNetworkPermission() {
        assertFalse(manifest().contains("ACCESS_LOCAL_NETWORK"))
        val routing = File(javaDir(), "com/v2ray/ang/amelia/AmeliaRouting.kt").readText()
        // local networks stay outside the VPN, so the app opens no connection into the LAN itself
        assertTrue(routing.contains("encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, AppConfig.DEFAULT_VPN_BYPASS_LAN)"))
        assertEquals("1", AppConfig.DEFAULT_VPN_BYPASS_LAN)
    }

    @Test
    fun d21_unusedUpstreamPartsAreGone() {
        val manifest = manifest()
        for (gone in listOf("ScSwitchActivity", "ScStartActivity", "ScStopActivity", "CoreRootService")) {
            assertFalse(manifest.contains(gone), gone)
        }
        val java = javaDir()
        for (gone in listOf("com/v2ray/ang/util/QRCodeDecoder.kt", "com/v2ray/ang/root", "com/v2ray/ang/service/DialerNativeService.kt", "com/v2ray/ang/service/DialerWebviewService.kt")) {
            assertFalse(File(java, gone).exists(), gone)
        }
        val gradle = File(resDir(), "../../../build.gradle.kts").readText()
        assertFalse(gradle.contains("zxing"))
        assertFalse(versions().contains("camerax"))
        val nm = File(java, "com/v2ray/ang/handler/NotificationManager.kt").readText()
        assertFalse(nm.contains("NotificationCompat.Builder? ="))
    }

    @Test
    fun d22_dependencies() {
        val v = versions()
        assertTrue(v.contains("okhttp = \"5.5.1\""))
        // MMKV stays on 1.x (2.x changes the data format)
        assertTrue(v.contains("mmkvStatic = \"1.3.17\""))
    }

    // ---- files ------------------------------------------------------------------------------------------------------

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")

    private fun manifest(): String = File(resDir(), "../AndroidManifest.xml").readText()

    private fun versions(): String = File(resDir(), "../../../../gradle/libs.versions.toml").readText()

    private fun mainSources(): List<File> = javaDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
