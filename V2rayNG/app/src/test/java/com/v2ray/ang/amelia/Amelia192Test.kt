package com.v2ray.ang.amelia

import com.v2ray.ang.amelia.AmeliaUpdater.ApkAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1.9.2: "Установить" with a downloaded APK that went missing (report from 1.8.3, Xiaomi, Android 16:
 * FileNotFoundException …/cache/updates/AmeliaNet-35.apk at AmeliaInstaller.startSession, 3 times).
 */
class Amelia192Test {

    @Test
    fun fileThereAndCurrentIsInstalled() {
        assertEquals(ApkAction.INSTALL, AmeliaUpdater.apkAction(exists = true, fileCode = 38, offeredCode = 38, missesInRow = 0))
        // earlier misses do not stop a good file
        assertEquals(ApkAction.INSTALL, AmeliaUpdater.apkAction(exists = true, fileCode = 38, offeredCode = 38, missesInRow = 5))
    }

    @Test
    fun fileGoneIsDownloadedAgain() {
        assertEquals(ApkAction.DOWNLOAD_AGAIN, AmeliaUpdater.apkAction(exists = false, fileCode = 35, offeredCode = 35, missesInRow = 0))
        // the notification found nothing at all
        assertEquals(ApkAction.DOWNLOAD_AGAIN, AmeliaUpdater.apkAction(exists = false, fileCode = null, offeredCode = 38, missesInRow = 0))
    }

    @Test
    fun fileOfAnOlderVersionIsNotInstalled() {
        // the case of the report: 1.8.4 (35) downloaded before, 1.9.x out since
        assertEquals(ApkAction.DOWNLOAD_AGAIN, AmeliaUpdater.apkAction(exists = true, fileCode = 35, offeredCode = 37, missesInRow = 0))
        // a file without our name, or no version known now: version.json is asked again
        assertEquals(ApkAction.DOWNLOAD_AGAIN, AmeliaUpdater.apkAction(exists = true, fileCode = null, offeredCode = 37, missesInRow = 0))
        assertEquals(ApkAction.DOWNLOAD_AGAIN, AmeliaUpdater.apkAction(exists = true, fileCode = 37, offeredCode = null, missesInRow = 0))
    }

    @Test
    fun twoMissesInARowGiveTheManualDownload() {
        assertEquals(2, AmeliaUpdater.MAX_APK_MISSES)
        assertEquals(ApkAction.MANUAL, AmeliaUpdater.apkAction(exists = false, fileCode = 38, offeredCode = 38, missesInRow = 1))
        assertEquals(ApkAction.MANUAL, AmeliaUpdater.apkAction(exists = true, fileCode = 35, offeredCode = 38, missesInRow = 1))
        assertEquals(ApkAction.MANUAL, AmeliaUpdater.apkAction(exists = false, fileCode = null, offeredCode = null, missesInRow = 4))
        // the page as in the bot
        assertEquals("https://sub.ameliajustamelia.com:9443/app/", AmeliaUpdater.MANUAL_DOWNLOAD_URL)
    }

    @Test
    fun versionFromTheFileName() {
        assertEquals(35, AmeliaUpdater.apkCodeOf("AmeliaNet-35.apk"))
        assertEquals(38, AmeliaUpdater.apkCodeOf(File("/data/user/0/com.ameliajustamelia.vpn/cache/updates/AmeliaNet-38.apk").name))
        // partial downloads and anything else are no APK of a version
        assertNull(AmeliaUpdater.apkCodeOf("AmeliaNet-38-0123456789abcdef.part"))
        assertNull(AmeliaUpdater.apkCodeOf("AmeliaNet_1.9.2_universal.apk"))
        assertNull(AmeliaUpdater.apkCodeOf(".lock"))
        // the same name the download writes and the cleanup keeps
        assertTrue(AmeliaUpdater.belongsTo("AmeliaNet-38.apk", 38))
    }

    @Test
    fun theCheckComesBeforeTheInstallerEverywhere() {
        val activity = File(javaDir(), "com/v2ray/ang/amelia/AmeliaActivity.kt").readText().replace("\r\n", "\n")
        val install = activity.substringAfter("private fun installUpdate(apkPath: String) {").substringBefore("\n    }\n")
        val check = install.indexOf("viewModel.apkAction(apkPath)")
        assertTrue(check >= 0, "installUpdate asks apkAction")
        assertTrue(check < install.indexOf("AmeliaApkCheck.check("), "before the APK check")
        assertTrue(check < install.indexOf("AmeliaInstaller.install("), "before the install")
        // a file deleted between the check and the install: no report, no system screen with it
        val catch = install.substringAfter("} catch (e: Exception) {")
        assertTrue(catch.indexOf("if (!apk.isFile)") in 0 until catch.indexOf("AmeliaCrash.nonFatal(\"update\""))
        // "Попробовать ещё раз" opens the system screen only after the same check
        val retry = activity.substringAfter("onInstallRetry = {").substringBefore("onInstallHelpDone")
        assertTrue(retry.indexOf("viewModel.apkAction(path)") in 0 until retry.indexOf("installViaSystemScreen("))
        // one way to download in the screen: AmeliaUpdater.download, no second copy
        val vm = File(javaDir(), "com/v2ray/ang/amelia/AmeliaViewModel.kt").readText()
        assertEquals(1, Regex("AmeliaUpdater\\.download\\(").findAll(vm).count())
        // the path on offer is always the checked file of the current version, never one kept from before
        assertFalse("ready?.absolutePath ?: it.updateApkPath" in vm)
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }

    private fun javaDir(): File = File(resDir(), "../java")
}
