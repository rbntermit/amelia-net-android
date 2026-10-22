package com.v2ray.ang.amelia

import android.content.pm.PackageInstaller
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

/** 1.5.1 (kept in 1.5.3): one update install at a time; a duplicate session is not a failure. */
class Amelia151Test {

    private val now = 1_792_600_000_000L

    /** Two attempts in a row: the decision AmeliaInstaller.install makes before creating a session. */
    @Test
    fun twoAttemptsCreateOneSession() {
        val sessions = mutableListOf<SessionView>()
        var owned: Owned? = null
        var created = 0
        fun attempt(at: Long) {
            val d = AmeliaInstaller.decideStart(AmeliaInstaller.phase(owned, sessions, at), hasConfirmScreen = false)
            if (d != StartDecision.NEW_SESSION) return
            created++
            sessions += SessionView(id = 40 + created, forUs = true, active = false, committed = true)
            owned = Owned(40 + created, at, awaitingConfirm = false)
        }
        attempt(now)
        attempt(now + 300) // second tap / notification / dialog button
        assertEquals(1, created)
    }

    @Test
    fun duplicateSessionGivesNoHelpScreenAndNoReport() {
        val samsung = "INSTALL_FAILED_DUPLICATE_PACKAGE: Scanning Failed.: Duplicate package " +
            "com.ameliajustamelia.vpn in pending install requests."
        assertEquals(Failure.DUPLICATE, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE, samsung))
        assertEquals(Failure.DUPLICATE, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_CONFLICT, "Duplicate package x in pending install requests"))
        val r = AmeliaInstaller.reaction(Failure.DUPLICATE)
        assertFalse(r.helpScreen)
        assertNull(r.report)
        assertFalse(r.systemInstaller)
    }

    @Test
    fun updateBlockedOnlyForRealRefusals() {
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_BLOCKED, ""))
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, "User rejected permissions", canInstallApps = false))
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERIFICATION_FAILURE: Auto Blocker"))
        assertEquals("update_blocked", AmeliaInstaller.reaction(Failure.REFUSED).report)
        assertTrue(AmeliaInstaller.reaction(Failure.REFUSED).helpScreen)
        // no space, broken file …: system installer, an ordinary "update" report, no help screen
        assertEquals(Failure.OTHER, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertEquals("update", AmeliaInstaller.reaction(Failure.OTHER).report)
        assertFalse(AmeliaInstaller.reaction(Failure.OTHER).helpScreen)
    }

    @Test
    fun afterTheVersionRoseNotInstalledIsCleared() {
        // the install began on 1.4.0 (13); the app now runs 1.5.0 (14)
        assertTrue(AmeliaInstaller.updatedSince(currentCode = 14, fromCode = 13))
        assertFalse(AmeliaInstaller.updatedSince(currentCode = 13, fromCode = 13))
        assertFalse(AmeliaInstaller.updatedSince(currentCode = 14, fromCode = 0))
        // a "blocked" mark for 14 left from the duplicate: not shown once 14 is installed
        assertFalse(AmeliaInstaller.showBlocked(blockedCode = 14, installedCode = 14, offeredCode = 14, busy = false))
        assertTrue(AmeliaInstaller.showBlocked(blockedCode = 14, installedCode = 13, offeredCode = 14, busy = false))
        // not while an install runs
        assertFalse(AmeliaInstaller.showBlocked(blockedCode = 14, installedCode = 13, offeredCode = 14, busy = true))
        assertEquals(Phase.IDLE, AmeliaInstaller.phase(null, emptyList(), now))
    }
}
