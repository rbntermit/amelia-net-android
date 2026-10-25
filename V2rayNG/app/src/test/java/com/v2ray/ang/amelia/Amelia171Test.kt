package com.v2ray.ang.amelia

import android.content.pm.PackageInstaller
import com.google.gson.JsonParser
import com.v2ray.ang.amelia.AmeliaInstaller.Failure
import com.v2ray.ang.amelia.AmeliaInstaller.PlayBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 1.7.1: Google Play Protect stopped the update — own screen, journal, report field, no crash report. */
class Amelia171Test {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    /** The exact text from the 1.6.0 reports (Android 13 and 16, Samsung and Xiaomi). */
    private val playProtect = "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed"

    // ---- recognising it ---------------------------------------------------------------------

    @Test
    fun verificationFailureIsPlayProtect() {
        // status=3 is STATUS_FAILURE_ABORTED: the text decides, with or without the install permission
        assertEquals(3, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(Failure.PLAY_PROTECT, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, playProtect, canInstallApps = true))
        assertEquals(Failure.PLAY_PROTECT, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, playProtect, canInstallApps = false))
        assertEquals(Failure.PLAY_PROTECT, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_BLOCKED, playProtect))
        assertEquals(Failure.PLAY_PROTECT, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERIFICATION_FAILURE"))
    }

    @Test
    fun samsungAutoBlockerStaysAPhoneRefusal() {
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERIFICATION_FAILURE: Auto Blocker"))
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_VERIFICATION_FAILURE: AutoBlocker is on"))
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_BLOCKED, ""))
    }

    @Test
    fun cancelInTheAndroidWindowKeepsItsOldBehaviour() {
        // ABORTED without VERIFICATION_FAILURE: "Отмена" in "Обновить приложение?" as before
        val rejected = "INSTALL_FAILED_ABORTED: User rejected permissions"
        assertEquals(Failure.CANCELLED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, rejected, canInstallApps = true))
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, rejected, canInstallApps = false))
        assertEquals("update_cancel", AmeliaInstaller.reaction(Failure.CANCELLED).report)
        assertFalse(AmeliaInstaller.reaction(Failure.CANCELLED).playProtectScreen)
    }

    // ---- not a crash ------------------------------------------------------------------------

    @Test
    fun playProtectNeverGoesToCrashReports() {
        val r = AmeliaInstaller.reaction(Failure.PLAY_PROTECT)
        assertNull(r.report)
        assertFalse(r.helpScreen)
        // the system installer would only ask Play Protect again
        assertFalse(r.systemInstaller)
        assertTrue(r.playProtectScreen)
        // no other failure opens the Play Protect window
        Failure.entries.filter { it != Failure.PLAY_PROTECT }.forEach { assertFalse(AmeliaInstaller.reaction(it).playProtectScreen, "$it") }
    }

    @Test
    fun playProtectHandlerDoesNotCallCrashReports() {
        val src = installerSource()
        val body = src.substringAfter("private fun onPlayProtect(").substringBefore("private fun onSessionFailed(")
        assertTrue(body.contains("AmeliaJournal.add"), "a journal line")
        assertFalse(body.contains("AmeliaCrash"), "no crash report")
        // the only crash call in onSessionFailed goes through reaction().report, which is null here
        val failed = src.substringAfter("private fun onSessionFailed(").substringBefore("private fun Intent.serializableIntent")
        assertFalse(Regex("""AmeliaCrash\.nonFatal\("update_blocked"""").containsMatchIn(failed))
    }

    // ---- remembering it ---------------------------------------------------------------------

    @Test
    fun blocksOfTheSameVersionAreCounted() {
        val first = AmeliaInstaller.recordPlayBlock(null, 28, 3, playProtect, now)
        assertEquals(PlayBlock(28, now, 1, 3, playProtect, offeredAt = 0, pending = true), first)
        val offered = AmeliaInstaller.markPlayOffered(first, now + 1)
        val second = AmeliaInstaller.recordPlayBlock(offered, 28, 3, playProtect, now + hour)
        assertEquals(2, second.count)
        assertTrue(second.pending)
        assertEquals(now + 1, second.offeredAt)
        // a newer version starts over
        val other = AmeliaInstaller.recordPlayBlock(second, 29, 3, playProtect, now + 2 * hour)
        assertEquals(1, other.count)
        assertEquals(0L, other.offeredAt)
        // Android's text is kept short
        assertEquals(160, AmeliaInstaller.recordPlayBlock(null, 28, 3, "x".repeat(500), now).message.length)
    }

    // ---- how often it is offered ------------------------------------------------------------

    private val fresh = AmeliaInstaller.recordPlayBlock(null, 28, 3, playProtect, now)
    private val seen = AmeliaInstaller.markPlayOffered(fresh, now)

    private fun offer(b: PlayBlock?, at: Long, manual: Boolean = false, busy: Boolean = false, installed: Int = 27, offered: Int? = 28) =
        AmeliaInstaller.offerPlayProtect(b, installed, offered, busy, at, manual)

    @Test
    fun freshBlockIsShownAtOnce() {
        assertTrue(offer(fresh, now))
        assertFalse(seen.pending)
        assertEquals(now, seen.offeredAt)
    }

    @Test
    fun afterTheWindowWasShownItWaitsADay() {
        assertFalse(offer(seen, now + 1))
        assertFalse(offer(seen, now + hour))
        assertFalse(offer(seen, now + day - 1))
        assertTrue(offer(seen, now + day))
        // the clock moved back: offer rather than stay silent for ever
        assertTrue(offer(seen, now - hour))
    }

    @Test
    fun manualCheckAlwaysShowsIt() {
        assertTrue(offer(seen, now + 1, manual = true))
        assertTrue(offer(seen, now + hour, manual = true))
    }

    @Test
    fun neverWhileInstallingOrForAnotherVersion() {
        assertFalse(offer(fresh, now, busy = true))
        assertFalse(offer(fresh, now, manual = true, busy = true))
        // that version is installed already
        assertFalse(offer(fresh, now, installed = 28))
        // the server offers another version (or none)
        assertFalse(offer(fresh, now, offered = 29))
        assertFalse(offer(fresh, now, offered = null))
        assertFalse(offer(null, now, manual = true))
    }

    @Test
    fun updateWindowAlsoWaitsADayAfterTheBlock() {
        assertFalse(AmeliaInstaller.offerUpdateDialog(cancelledAt = 0, now = now + hour, required = false, playOfferedAt = now))
        assertTrue(AmeliaInstaller.offerUpdateDialog(cancelledAt = 0, now = now + day, required = false, playOfferedAt = now))
        assertTrue(AmeliaInstaller.offerUpdateDialog(cancelledAt = 0, now = now, required = false, playOfferedAt = 0))
        // a required update is always offered
        assertTrue(AmeliaInstaller.offerUpdateDialog(cancelledAt = 0, now = now + 1, required = true, playOfferedAt = now))
        // the 1.5.5 cancel pause still holds on its own
        assertFalse(AmeliaInstaller.offerUpdateDialog(cancelledAt = now, now = now + hour, required = false))
    }

    // ---- /app/report ------------------------------------------------------------------------

    private fun body(play: AmeliaReport.PlayProtect?) = AmeliaReport.Body(
        key = "SECRETKEY", ts = 1_800_000_000, app = "1.7.1", code = 27, xray = "26.10.21", hy2 = "xray-26.10.21",
        android = "16", device = "Xiaomi 2409BRN2CY", tv = false, net = "mobile", op = "MTS RUS", path = "direct",
        active = null, bypassRu = true, comment = "", servers = emptyList(), playProtect = play,
    )

    @Test
    fun reportCarriesTheBlockWhileTheVersionIsNotInstalled() {
        val block = AmeliaInstaller.recordPlayBlock(fresh, 28, 3, playProtect, now + hour)
        val p = AmeliaReport.playProtect(block, installedCode = 27)
        assertEquals(AmeliaReport.PlayProtect(28, (now + hour) / 1000, 2, 3, playProtect), p)
        assertNull(AmeliaReport.playProtect(block, installedCode = 28))
        assertNull(AmeliaReport.playProtect(null, installedCode = 27))

        val json = JsonParser.parseString(AmeliaReport.toJson(body(p))).asJsonObject
        val field = json.getAsJsonObject("play_protect")
        assertNotNull(field)
        assertEquals(28, field.get("code").asInt)
        assertEquals((now + hour) / 1000, field.get("at").asLong)
        assertEquals(2, field.get("count").asInt)
        assertEquals(3, field.get("status").asInt)
        assertEquals(playProtect, field.get("msg").asString)
        // the rest of the contract is untouched
        assertEquals("SECRETKEY", json.get("key").asString)
        assertFalse(json.has("playProtect"))
    }

    @Test
    fun reportWithoutABlockHasNoField() {
        val json = JsonParser.parseString(AmeliaReport.toJson(body(null))).asJsonObject
        assertFalse(json.has("play_protect"))
        assertFalse(json.has("playProtect"))
        assertFalse(AmeliaReport.plainText(body(null)).contains("Play Защита"))
    }

    @Test
    fun plainTextForTheBotMentionsIt() {
        val text = AmeliaReport.plainText(body(AmeliaReport.PlayProtect(28, 1, 3, 3, playProtect)))
        assertTrue(text.contains("Google Play Защита не дала установить обновление (версия 28), сколько раз: 3"))
        assertFalse(text.contains("SECRETKEY"))
    }

    // ---- texts ------------------------------------------------------------------------------

    @Test
    fun screenTextsAndButtonsExist() {
        val strings = File(resDir(), "values/amelia_strings.xml").readText()
        listOf(
            "amelia_play_protect_title" to "Google Play Защита не дала установить обновление",
            "amelia_play_protect_text" to "Нажмите «Попробовать снова» и в окне Google выберите «Проверить» (или «Установить без проверки»).",
            "amelia_play_protect_wifi" to "Если вы на мобильном интернете — подключитесь к Wi‑Fi: проверке Google нужен доступ к своим серверам.",
            "amelia_play_protect_retry" to "Попробовать снова",
            "amelia_play_protect_telegram" to "Скачать в Telegram-боте",
            "amelia_update_later" to "Позже",
        ).forEach { (name, text) -> assertTrue(strings.contains("<string name=\"$name\">$text</string>"), name) }
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun installerSource(): String = File(resDir(), "../java/com/v2ray/ang/amelia/AmeliaInstaller.kt").readText()
}
