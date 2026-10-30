package com.v2ray.ang.amelia

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

/** 1.8.0 "Поддержка": the chat with support in the app (AmeliaSupport). */
class Amelia180Test {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun item(id: String, d: String, tsMs: Long, x: String = "text") = AmeliaSupport.Item(id, tsMs / 1000, d, x)

    // ---- the thread's name ----------------------------------------------------------------

    @Test
    fun hashMatchesTheServer() {
        assertEquals("d6f44099238550fcf27732bc", AmeliaSupport.hash("9d985e1fddce376a"))
    }

    @Test
    fun hashIs24HexAndDependsOnTheKey() {
        val h = AmeliaSupport.hash("another-key")
        assertEquals(24, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(h == AmeliaSupport.hash("9d985e1fddce376a"))
    }

    // ---- requests -------------------------------------------------------------------------

    @Test
    fun textIsTrimmedAndCutTo1500() {
        assertEquals("привет", AmeliaSupport.cut("  привет \n"))
        assertEquals(1500, AmeliaSupport.cut("я".repeat(2000)).length)
        assertEquals(1500, AmeliaSupport.cut(" " + "a".repeat(1500) + "b").length)
        assertEquals("a".repeat(1500), AmeliaSupport.cut(" " + "a".repeat(1500) + "b"))
    }

    @Test
    fun sendRequestFollowsTheContract() {
        val json = JsonParser.parseString(
            AmeliaSupport.sendRequest("KEY", "x".repeat(1600), "a".repeat(5000), "1.8.0"),
        ).asJsonObject
        assertEquals(setOf("key", "text", "about", "app"), json.keySet())
        assertEquals("KEY", json["key"].asString)
        assertEquals(1500, json["text"].asString.length)
        assertEquals(4000, json["about"].asString.length)
        assertEquals("1.8.0", json["app"].asString)
    }

    @Test
    fun threadRequestHasOnlyTheHash() {
        val json = JsonParser.parseString(AmeliaSupport.threadRequest("d6f44099238550fcf27732bc")).asJsonObject
        assertEquals(setOf("h"), json.keySet())
        assertEquals("d6f44099238550fcf27732bc", json["h"].asString)
    }

    @Test
    fun aboutHasNoKeyAndDropsOldJournalLinesFirst() {
        val journal = (1..100).map { "29.10.2026 14:%02d строка журнала номер $it ".padEnd(150, '.').take(150) }
        val about = AmeliaSupport.about("Amelia.Net 1.8.0 (31)", "Финляндия 1", vpn = true, journal = journal)
        assertTrue(about.length <= AmeliaSupport.MAX_ABOUT)
        assertTrue(about.startsWith("Amelia.Net 1.8.0 (31)"))
        assertTrue("Локация: Финляндия 1" in about)
        assertTrue("VPN включён: да" in about)
        // the newest line stays, the oldest went
        assertTrue("номер 100 " in about)
        assertFalse("номер 1 " in about)

        val short = AmeliaSupport.about("v", null, vpn = false, journal = emptyList())
        assertEquals("v\nVPN включён: нет", short)
    }

    // ---- answers --------------------------------------------------------------------------

    @Test
    fun sendAnswers() {
        assertEquals(
            AmeliaSupport.SendResult.Sent("W-1A2B3C"),
            AmeliaSupport.parseSend(200, """{"ok":true,"id":"W-1A2B3C","h":"d6f44099238550fcf27732bc"}"""),
        )
        assertEquals(AmeliaSupport.SendResult.Empty, AmeliaSupport.parseSend(400, """{"err":"empty"}"""))
        assertEquals(AmeliaSupport.SendResult.BadKey, AmeliaSupport.parseSend(400, """{"err":"bad key"}"""))
        assertEquals(AmeliaSupport.SendResult.TooOften, AmeliaSupport.parseSend(429, ""))
        assertEquals(AmeliaSupport.SendResult.Failed, AmeliaSupport.parseSend(500, """{"err":"x"}"""))
        assertEquals(AmeliaSupport.SendResult.Failed, AmeliaSupport.parseSend(null, null))
        // garbage and HTML instead of JSON: not sent, not a crash
        assertEquals(AmeliaSupport.SendResult.Failed, AmeliaSupport.parseSend(200, "<html><body>Wi-Fi login</body></html>"))
        assertEquals(AmeliaSupport.SendResult.Failed, AmeliaSupport.parseSend(200, """{"ok":false,"id":"W-1"}"""))
        assertEquals(AmeliaSupport.SendResult.Failed, AmeliaSupport.parseSend(200, """{"ok":true,"id":""}"""))
        assertEquals(AmeliaSupport.SendResult.Failed, AmeliaSupport.parseSend(400, "<html>"))
    }

    @Test
    fun threadIsParsedOldestFirst() {
        val items = AmeliaSupport.parseThread(
            """{"ok":true,"items":[
                {"id":"R-17","ts":1700000000,"d":"in","x":"📨 Отчёт R-17 из приложения: …"},
                {"id":"A-1","ts":1700000600,"d":"out","x":"Здравствуйте! Попробуйте Финляндию 2"},
                {"id":"W-2","ts":1700000700,"d":"in","x":"Спасибо"}]}""",
        )
        assertEquals(listOf("R-17", "A-1", "W-2"), items?.map { it.id })
        assertEquals(listOf(false, true, false), items?.map { it.fromSupport })
        assertEquals(1_700_000_600L, items?.get(1)?.ts)
    }

    @Test
    fun garbageAndHtmlAreNoThreadNotAnError() {
        assertNull(AmeliaSupport.parseThread(null))
        assertNull(AmeliaSupport.parseThread(""))
        assertNull(AmeliaSupport.parseThread("<!DOCTYPE html><html><body>502 Bad Gateway</body></html>"))
        assertNull(AmeliaSupport.parseThread("not json at all {"))
        assertNull(AmeliaSupport.parseThread("[]"))
        assertNull(AmeliaSupport.parseThread("""{"ok":false,"items":[]}"""))
        assertNull(AmeliaSupport.parseThread("""{"ok":true,"items":"x"}"""))
        assertEquals(emptyList<AmeliaSupport.Item>(), AmeliaSupport.parseThread("""{"ok":true}"""))
    }

    @Test
    fun brokenMessagesAreLeftOut() {
        val items = AmeliaSupport.parseThread(
            """{"ok":true,"items":[null,{"id":"","d":"in","x":"a"},{"id":"1","d":"sideways","x":"b"},
               {"id":"2","d":"out"},{"id":"3","ts":-5,"d":"in","x":"c"}]}""",
        )
        assertEquals(listOf("2", "3"), items?.map { it.id })
        assertEquals("", items?.get(0)?.x)
        assertEquals(0L, items?.get(1)?.ts)
    }

    // ---- waiting for an answer ------------------------------------------------------------

    @Test
    fun waitingOnlyWhileTheLastMessageIsOursAndYoungerThan7Days() {
        val ours = AmeliaSupport.Store(items = listOf(item("1", "out", now - 3 * day), item("2", "in", now - 2 * day)))
        assertTrue(AmeliaSupport.waiting(ours, now))
        assertFalse(AmeliaSupport.waiting(ours, now + 6 * day))

        val answered = ours.copy(items = ours.items + item("3", "out", now - day))
        assertFalse(AmeliaSupport.waiting(answered, now))
        assertFalse(AmeliaSupport.waiting(AmeliaSupport.Store(), now))
    }

    @Test
    fun aSentReportIsAwaitedUntilAFetchedThreadHasIt() {
        // a report went out: the kept thread does not have it yet
        val answered = AmeliaSupport.Store(h = "h", items = listOf(item("1", "in", now - 2 * day), item("2", "out", now - day)))
        val sent = answered.copy(sentAt = now)
        assertTrue(AmeliaSupport.waiting(sent, now + 1000))
        assertFalse(AmeliaSupport.waiting(sent, now + AmeliaSupport.WAIT_MS))

        // a fetch started before the send keeps the mark, one started after it drops it
        assertEquals(now, AmeliaSupport.withThread(sent, answered.items, now - 1).sentAt)
        val fetched = AmeliaSupport.withThread(sent, answered.items + item("R-9", "in", now), now + 1000)
        assertEquals(0L, fetched.sentAt)
        assertTrue(AmeliaSupport.waiting(fetched, now + 2000))
    }

    @Test
    fun aSentMessageIsShownRightAway() {
        val s = AmeliaSupport.withSent(AmeliaSupport.Store(h = "h"), "W-1", "  " + "z".repeat(1600), now)
        assertEquals(1, s.items.size)
        assertEquals("W-1", s.items[0].id)
        assertEquals(AmeliaSupport.DIR_IN, s.items[0].d)
        assertEquals(1500, s.items[0].x.length)
        assertEquals(now, s.sentAt)
        assertTrue(AmeliaSupport.waiting(s, now))
    }

    @Test
    fun aMessageSentWhileTheThreadWasOnItsWayIsNotLost() {
        val old = listOf(item("A-1", "out", now - day))
        // the screen asked at now - 2 s; the message went out at now; then the answer came
        val sent = AmeliaSupport.withSent(AmeliaSupport.Store(h = "h", items = old), "W-5", "привет", now)
        val fetched = AmeliaSupport.withThread(sent, old, now - 2000)
        assertEquals(listOf("A-1", "W-5"), fetched.items.map { it.id })
        assertEquals(now, fetched.sentAt)
        // the next fetch has it from the server: no double
        val next = AmeliaSupport.withThread(fetched, old + item("W-5", "in", now), now + 30_000)
        assertEquals(listOf("A-1", "W-5"), next.items.map { it.id })
        assertEquals(0L, next.sentAt)
    }

    @Test
    fun anotherKeyStartsAnEmptyThread() {
        val s = AmeliaSupport.Store(h = "aaa", items = listOf(item("1", "in", now)), seen = "x", notified = "y")
        assertEquals(s, AmeliaSupport.forHash(s, "aaa"))
        assertEquals(AmeliaSupport.Store(h = "bbb"), AmeliaSupport.forHash(s, "bbb"))
    }

    // ---- new answers and notifications ----------------------------------------------------

    @Test
    fun aNewAnswerIsNotifiedOnce() {
        val waiting = AmeliaSupport.Store(h = "h", items = listOf(item("1", "in", now)))
        assertNull(AmeliaSupport.newAnswer(waiting))

        val answered = waiting.copy(items = waiting.items + item("A-1", "out", now + 60_000, "Ответ"))
        val first = AmeliaSupport.newAnswer(answered)
        assertEquals("A-1", first?.id)

        // the worker writes it down: the next check (same thread) does not notify again
        val notified = answered.copy(notified = first!!.id)
        assertNull(AmeliaSupport.newAnswer(notified))

        // a second answer is new again
        val second = notified.copy(items = notified.items + item("A-2", "out", now + 120_000))
        assertEquals("A-2", AmeliaSupport.newAnswer(second)?.id)
    }

    @Test
    fun anAnswerSeenOnTheScreenIsNotNotified() {
        val answered = AmeliaSupport.Store(h = "h", items = listOf(item("1", "in", now), item("A-1", "out", now + 1)))
        assertTrue(AmeliaSupport.unread(answered))
        val seen = AmeliaSupport.seen(answered)
        assertEquals("A-1", seen.seen)
        assertFalse(AmeliaSupport.unread(seen))
        assertNull(AmeliaSupport.newAnswer(seen))
    }

    @Test
    fun anOldAnswerBeforeOurLastMessageIsNoNotification() {
        // answers to reports from before 1.8.0, then our new message: nothing to notify yet
        val s = AmeliaSupport.Store(h = "h", items = listOf(item("A-0", "out", now - day), item("W-1", "in", now)))
        assertNull(AmeliaSupport.newAnswer(s))
        // but the tile shows the answer the screen has not shown
        assertTrue(AmeliaSupport.unread(s))
        assertFalse(AmeliaSupport.unread(AmeliaSupport.seen(s)))
    }

    @Test
    fun noAnswerNoDot() {
        assertFalse(AmeliaSupport.unread(AmeliaSupport.Store()))
        val s = AmeliaSupport.Store(items = listOf(item("1", "in", now)))
        assertFalse(AmeliaSupport.unread(s))
        assertEquals(s, AmeliaSupport.seen(s))
    }

    // ---- the screen -----------------------------------------------------------------------

    @Test
    fun timeIsShortToday() {
        val noon = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 5); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("12:05", AmeliaSupport.time(noon / 1000, noon + 3_600_000))
        val time = AmeliaSupport.time(noon / 1000, noon + 2 * day)
        assertTrue(time.matches(Regex("""\d\d\.\d\d 12:05""")), time)
    }

    // ---- "🩺 Не работает?" ----------------------------------------------------------------

    @Test
    fun notWorkingOpensTheStatePageOnlyWithoutVpnAndTelegram() {
        assertEquals(AmeliaSite.NotWorking.STATUS_PAGE, AmeliaSite.notWorking(vpnOn = false, telegramInstalled = false))
        assertEquals(AmeliaSite.NotWorking.REPORT, AmeliaSite.notWorking(vpnOn = false, telegramInstalled = true))
        assertEquals(AmeliaSite.NotWorking.REPORT, AmeliaSite.notWorking(vpnOn = true, telegramInstalled = false))
        assertEquals(AmeliaSite.NotWorking.REPORT, AmeliaSite.notWorking(vpnOn = true, telegramInstalled = true))
        assertEquals("https://msk2.ameliajustamelia.com:9443/status", AmeliaSite.statusUrl("https://msk2.ameliajustamelia.com:9443"))
    }
}
