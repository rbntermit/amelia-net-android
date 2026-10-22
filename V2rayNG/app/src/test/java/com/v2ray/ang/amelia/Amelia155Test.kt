package com.v2ray.ang.amelia

import android.content.pm.PackageInstaller
import com.v2ray.ang.amelia.AmeliaInstaller.Failure
import com.v2ray.ang.amelia.AmeliaInstaller.Owned
import com.v2ray.ang.amelia.AmeliaInstaller.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.5.5: "Отмена" in "Обновить приложение?" is not a refusal. */
class Amelia155Test {

    private val now = 1_792_700_000_000L
    private val day = 24 * 60 * 60 * 1000L
    /** What Android sends in both cases (seen in /app/crash 10 times a day on 5 phones). */
    private val rejected = "INSTALL_FAILED_ABORTED: User rejected permissions"

    @Test
    fun cancelBringsTheCardBackWithoutUpdateBlocked() {
        val f = AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, rejected, canInstallApps = true)
        assertEquals(Failure.CANCELLED, f)
        val r = AmeliaInstaller.reaction(f)
        assertFalse(r.helpScreen, "no help screen")
        assertFalse(r.systemInstaller, "no system installer opened by itself")
        assertEquals("update_cancel", r.report)
        // the session is finished: the button is "Установить" again (with the 1.5.4 hint)
        assertEquals(Phase.IDLE, AmeliaInstaller.phase(Owned(5, now, awaitingConfirm = true), emptyList(), now))
        // and no "Телефон не дал установить": the blocked mark is not set for a cancel
        assertFalse(AmeliaInstaller.showBlocked(blockedCode = 0, installedCode = 18, offeredCode = 19, busy = false))
    }

    @Test
    fun updateCancelAtMostOnceADay() {
        var last = 0L
        var sent = 0
        fun cancel(at: Long) {
            if (AmeliaInstaller.shouldReportCancel(last, at)) {
                sent++
                last = at
            }
        }
        cancel(now)
        cancel(now + 60_000)
        cancel(now + 5 * 60 * 60 * 1000L)
        assertEquals(1, sent)
        cancel(now + day)
        assertEquals(2, sent)
    }

    @Test
    fun theWindowDoesNotPopUpByItselfForADay() {
        assertFalse(AmeliaInstaller.offerUpdateDialog(cancelledAt = now, now = now + day - 1, required = false))
        assertTrue(AmeliaInstaller.offerUpdateDialog(cancelledAt = now, now = now + day, required = false))
        assertTrue(AmeliaInstaller.offerUpdateDialog(cancelledAt = 0, now = now, required = false))
        // a required update is always offered
        assertTrue(AmeliaInstaller.offerUpdateDialog(cancelledAt = now, now = now + 1, required = true))
    }

    @Test
    fun unknownAppsNotAllowedIsARealBlock() {
        val f = AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, rejected, canInstallApps = false)
        assertEquals(Failure.REFUSED, f)
        val r = AmeliaInstaller.reaction(f)
        assertTrue(r.helpScreen)
        assertTrue(r.systemInstaller)
        assertEquals("update_blocked", r.report)
        // Samsung Auto Blocker / Play Protect stay refusals even with the permission
        assertEquals(Failure.REFUSED, AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_BLOCKED, "", canInstallApps = true))
        assertEquals(
            Failure.REFUSED,
            AmeliaInstaller.classify(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_VERIFICATION_FAILURE: Auto Blocker", canInstallApps = true),
        )
    }
}
