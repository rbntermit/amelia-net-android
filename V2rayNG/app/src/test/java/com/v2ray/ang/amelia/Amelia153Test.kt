package com.v2ray.ang.amelia

import android.content.pm.PackageInstaller
import com.v2ray.ang.amelia.AmeliaInstaller.ConfirmRoute
import com.v2ray.ang.amelia.AmeliaInstaller.Failure
import com.v2ray.ang.amelia.AmeliaInstaller.Owned
import com.v2ray.ang.amelia.AmeliaInstaller.Phase
import com.v2ray.ang.amelia.AmeliaInstaller.SessionView
import com.v2ray.ang.amelia.AmeliaInstaller.StartDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.5.3: no endless "Устанавливается…". */
class Amelia153Test {

    private val now = 1_792_650_000_000L
    private val committed = SessionView(id = 7, forUs = true, active = false, committed = true)

    @Test
    fun pendingUserActionOpensTheConfirmation() {
        // STATUS_PENDING_USER_ACTION is never treated as a failure or as "pending install"
        val owned = Owned(7, now, awaitingConfirm = true)
        assertEquals(Phase.AWAITING_CONFIRM, AmeliaInstaller.phase(owned, listOf(committed), now))
        // visible app: the screen right away; in the background: a notification that opens it
        assertEquals(ConfirmRoute.ACTIVITY, AmeliaInstaller.confirmRoute(appVisible = true))
        assertEquals(ConfirmRoute.NOTIFICATION, AmeliaInstaller.confirmRoute(appVisible = false))
        // back in the app without confirming: "Установить" is not blocked, it opens the confirmation again
        assertEquals(StartDecision.REOPEN_CONFIRM, AmeliaInstaller.decideStart(Phase.AWAITING_CONFIRM, hasConfirmScreen = true))
        // confirmation lost: a new session instead of waiting for ever
        assertEquals(StartDecision.NEW_SESSION, AmeliaInstaller.decideStart(Phase.AWAITING_CONFIRM, hasConfirmScreen = false))
        // waiting for the person never times out into "update_stuck"
        assertEquals(Phase.AWAITING_CONFIRM, AmeliaInstaller.phase(owned, listOf(committed), now + 10 * AmeliaInstaller.STUCK_MS))
    }

    @Test
    fun processDeathWithAWaitingSessionFreesTheButton() {
        // 1.5.1/1.5.2 counted this committed session as "installing" for ever
        val leftOver = listOf(committed, SessionView(id = 8, forUs = true, active = true, committed = false))
        // the new process leads nothing: both are abandoned, the button says "Установить"
        assertEquals(listOf(7, 8), AmeliaInstaller.orphans(leftOver, ownedId = null))
        assertEquals(Phase.IDLE, AmeliaInstaller.phase(null, leftOver, now))
        assertEquals(StartDecision.NEW_SESSION, AmeliaInstaller.decideStart(Phase.IDLE, hasConfirmScreen = false))
        // the session this process leads is kept; other apps' sessions are not ours to touch
        assertEquals(listOf(8), AmeliaInstaller.orphans(leftOver, ownedId = 7))
        assertEquals(emptyList<Int>(), AmeliaInstaller.orphans(listOf(SessionView(9, forUs = false, active = true, committed = true)), null))
    }

    @Test
    fun threeMinutesWithoutAResultGiveTheButtonBackAndReport() {
        val owned = Owned(7, now, awaitingConfirm = false)
        assertEquals(Phase.INSTALLING, AmeliaInstaller.phase(owned, listOf(committed), now + AmeliaInstaller.STUCK_MS - 1))
        val stuck = AmeliaInstaller.phase(owned, listOf(committed), now + AmeliaInstaller.STUCK_MS)
        assertEquals(Phase.STUCK, stuck)
        val s = AmeliaInstaller.settle(stuck)
        assertEquals(Phase.IDLE, s.phase, "button back to Установить")
        assertTrue(s.abandonOwned)
        assertTrue(s.reportStuck)
        // installing normally: nothing abandoned, nothing reported
        assertFalse(AmeliaInstaller.settle(Phase.INSTALLING).reportStuck)
        // the session is gone (finished or dropped by Android): idle without a report
        assertEquals(Phase.IDLE, AmeliaInstaller.phase(owned, emptyList(), now + 10_000))

        val report = AmeliaInstaller.stuckReport(listOf("-1"), listOf(committed), "16", 36, "samsung")
        assertTrue("statuses=[-1]" in report)
        assertTrue("sessions=1 [7:idle/committed]" in report)
        assertTrue("android=16 (sdk 36)" in report)
        assertTrue("maker=samsung" in report)
    }

    @Test
    fun duplicateStillNoScreenNoReport() {
        val f = AmeliaInstaller.classify(
            PackageInstaller.STATUS_FAILURE,
            "INSTALL_FAILED_DUPLICATE_PACKAGE: Scanning Failed.: Duplicate package com.ameliajustamelia.vpn in pending install requests.",
        )
        assertEquals(Failure.DUPLICATE, f)
        assertNull(AmeliaInstaller.reaction(f).report)
        assertFalse(AmeliaInstaller.reaction(f).helpScreen)
    }

    @Test
    fun doubleTapOneSession() {
        val owned = Owned(7, now, awaitingConfirm = false)
        val phase = AmeliaInstaller.phase(owned, listOf(committed), now + 500)
        assertEquals(StartDecision.ALREADY_RUNNING, AmeliaInstaller.decideStart(phase, hasConfirmScreen = false))
    }
}
