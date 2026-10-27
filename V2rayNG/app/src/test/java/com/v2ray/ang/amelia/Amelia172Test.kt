package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/** 1.7.2: a damaged geoip.dat / geosite.dat no longer stops the VPN. */
class Amelia172Test {

    private val geoip = AppConfig.GEOIP_DAT
    private val geosite = AppConfig.GEOSITE_DAT

    /** The error from the 1.7.1 report (Xiaomi 23127PN0CG, Android 16, core_start). */
    private val reported = "go.Universe\$proxyerror: config error: infra/conf: failed to build routing configuration > " +
        "infra/conf: invalid field rule > common/geodata: illegal ip rule: geoip:ru > " +
        "failed to check code RU from geoip.dat > invalid body length: 134398"

    // ---- synthetic bases --------------------------------------------------------------------

    private fun varint(v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (x >= 0x80) {
            out.write((x and 0x7F) or 0x80); x = x ushr 7
        }
        out.write(x)
        return out.toByteArray()
    }

    /** One GeoIP / GeoSite entry: country_code, then a body of [bodySize] bytes (field 2). */
    private fun entry(code: String, bodySize: Int): ByteArray {
        val name = code.toByteArray()
        val inner = byteArrayOf(0x0A) + varint(name.size) + name + byteArrayOf(0x12) + varint(bodySize) + ByteArray(bodySize) { 7 }
        return byteArrayOf(0x0A) + varint(inner.size) + inner
    }

    private fun base(vararg entries: Pair<String, Int>): ByteArray = entries.fold(ByteArray(0)) { acc, (c, n) -> acc + entry(c, n) }

    private fun scan(bytes: ByteArray) = AmeliaGeoUpdater.scan(ByteArrayInputStream(bytes), bytes.size.toLong())

    // ---- recognising a whole base -----------------------------------------------------------

    @Test
    fun wholeBaseListsItsCodes() {
        val s = scan(base("RU" to 134_000, "private" to 300))
        assertTrue(s.ok, s.error)
        assertEquals(setOf("RU", "PRIVATE"), s.codes)
        assertTrue(scan(base("CATEGORY-RU" to 30_000)).codes.contains("CATEGORY-RU"))
    }

    @Test
    fun baseCutShortIsDamaged() {
        // the reported case: the entry says 134398 bytes, the file ends earlier
        val whole = base("RU" to 134_380)
        for (cut in listOf(whole.size - 1, whole.size - 74, 10, 3, 1)) {
            val s = scan(whole.copyOf(cut))
            assertFalse(s.ok, "cut at $cut")
        }
        assertTrue(scan(whole.copyOf(whole.size - 74)).error!!.contains("invalid body length"))
    }

    @Test
    fun garbageIsNotABase() {
        assertFalse(scan("<html>502 Bad Gateway</html>".toByteArray()).ok)
        assertFalse(scan(ByteArray(0)).ok)
        // trailing bytes after the last entry
        assertFalse(scan(base("RU" to 2000) + byteArrayOf(0x55)).ok)
        // the declared size is larger than the stream
        val b = base("RU" to 2000)
        assertFalse(AmeliaGeoUpdater.scan(ByteArrayInputStream(b), b.size + 10L).ok)
    }

    @Test
    fun fileCheckWantsTheListTheRoutingUses(@TempDir dir: File) {
        val ip = File(dir, "a").apply { writeBytes(base("RU" to 5000)) }
        val site = File(dir, "b").apply { writeBytes(base("CATEGORY-RU" to 5000)) }
        assertNull(AmeliaGeoUpdater.problem(ip, geoip))
        assertNull(AmeliaGeoUpdater.problem(site, geosite))
        // a whole base without the list Xray will be asked for would stop it as well
        assertEquals("no CATEGORY-RU", AmeliaGeoUpdater.problem(ip, geosite))
        assertEquals("no RU", AmeliaGeoUpdater.problem(File(dir, "c").apply { writeBytes(base("CN" to 5000)) }, geoip))
        // tiny, missing, cut short
        assertNotNull(AmeliaGeoUpdater.problem(File(dir, "d").apply { writeBytes(base("RU" to 10)) }, geoip))
        assertEquals("missing", AmeliaGeoUpdater.problem(File(dir, "none"), geoip))
        val cut = File(dir, "e").apply { writeBytes(base("RU" to 5000).copyOf(4000)) }
        assertNotNull(AmeliaGeoUpdater.problem(cut, geoip))
    }

    @Test
    fun builtInBasesOfTheApkPassTheCheck(@TempDir dir: File) {
        val aar = File(resDir(), "../../../libs/libv2ray.aar")
        assumeTrue(aar.isFile, "libv2ray.aar is built by CI before the tests")
        ZipFile(aar).use { zip ->
            for (name in listOf(geoip, geosite)) {
                val f = File(dir, name)
                zip.getInputStream(zip.getEntry("assets/$name")).use { input -> f.outputStream().use { input.copyTo(it) } }
                assertNull(AmeliaGeoUpdater.problem(f, name), name)
                // and the same base cut short is caught
                val cut = File(dir, "$name.cut").apply { writeBytes(f.readBytes().copyOf((f.length() * 2 / 3).toInt())) }
                assertNotNull(AmeliaGeoUpdater.problem(cut, name), "$name cut")
            }
        }
    }

    @Test
    fun downloadMustBeComplete() {
        assertTrue(AmeliaGeoUpdater.sizeMatches(contentLength = 134_398, actual = 134_398))
        assertFalse(AmeliaGeoUpdater.sizeMatches(contentLength = 134_398, actual = 134_324))
        // no Content-Length (chunked): the parse decides
        assertTrue(AmeliaGeoUpdater.sizeMatches(contentLength = -1, actual = 5))
    }

    // ---- atomic replace ---------------------------------------------------------------------

    /** What rename(2) does on Android; java.io.File.renameTo cannot replace on the Windows test JVM. */
    private val atomicMove: (File, File) -> Boolean = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); true
    }

    @Test
    fun goodFileReplacesTheWorkingOne(@TempDir dir: File) {
        val target = File(dir, geoip).apply { writeBytes(base("RU" to 3000)) }
        val fresh = base("RU" to 4000)
        val tmp = File(dir, "geoip.dat.1.download").apply { writeBytes(fresh) }
        assertTrue(AmeliaGeoUpdater.install(tmp, target, atomicMove) { AmeliaGeoUpdater.problem(it, geoip) == null })
        assertTrue(fresh.contentEquals(target.readBytes()))
        assertFalse(tmp.exists())
    }

    @Test
    fun damagedDownloadNeverTouchesTheWorkingFile(@TempDir dir: File) {
        val working = base("RU" to 3000)
        val target = File(dir, geoip).apply { writeBytes(working) }
        val tmp = File(dir, "geoip.dat.2.download").apply { writeBytes(base("RU" to 4000).copyOf(2500)) }
        assertFalse(AmeliaGeoUpdater.install(tmp, target, atomicMove) { AmeliaGeoUpdater.problem(it, geoip) == null })
        assertTrue(working.contentEquals(target.readBytes()))
        assertFalse(tmp.exists(), "the half file is removed")
    }

    @Test
    fun failedMoveKeepsTheWorkingFile(@TempDir dir: File) {
        val working = base("RU" to 3000)
        val target = File(dir, geoip).apply { writeBytes(working) }
        for (move in listOf<(File, File) -> Boolean>({ _, _ -> false }, { _, _ -> throw IOException("disk full") })) {
            val tmp = File(dir, "geoip.dat.3.download").apply { writeBytes(base("RU" to 4000)) }
            assertFalse(AmeliaGeoUpdater.install(tmp, target, move) { true })
            assertTrue(working.contentEquals(target.readBytes()))
            assertFalse(tmp.exists())
        }
    }

    @Test
    fun firstFileIsPutInPlaceByTheDefaultMove(@TempDir dir: File) {
        val target = File(dir, geosite)
        val tmp = File(dir, "geosite.dat.4.builtin").apply { writeBytes(base("CATEGORY-RU" to 3000)) }
        assertTrue(AmeliaGeoUpdater.install(tmp, target) { AmeliaGeoUpdater.problem(it, geosite) == null })
        assertNull(AmeliaGeoUpdater.problem(target, geosite))
    }

    @Test
    fun onlyDamagedPresentFilesAreRestored() {
        assertEquals(listOf(geoip), AmeliaGeoUpdater.damaged(mapOf(geoip to false, geosite to true)))
        assertEquals(emptyList<String>(), AmeliaGeoUpdater.damaged(mapOf(geoip to true, geosite to true)))
        assertEquals(emptyList<String>(), AmeliaGeoUpdater.damaged(emptyMap()))
    }

    // ---- the core start error ---------------------------------------------------------------

    @Test
    fun geoErrorIsRecognised() {
        assertTrue(AmeliaGeoUpdater.isGeoError(reported))
        assertTrue(AmeliaGeoUpdater.isGeoError("config error: failed to load geosite: CATEGORY-RU"))
        assertTrue(AmeliaGeoUpdater.isGeoError("common/geodata: failed to open file: geosite.dat > no such file"))
        // other start failures are not about the bases
        assertFalse(AmeliaGeoUpdater.isGeoError("Core failed to start"))
        assertFalse(AmeliaGeoUpdater.isGeoError("config error: infra/conf: failed to build outbound config > invalid uuid"))
        assertFalse(AmeliaGeoUpdater.isGeoError("No server selected"))
        assertFalse(AmeliaGeoUpdater.isGeoError(null))
        // the text may sit deeper in the cause chain
        val wrapped = IllegalStateException("start failed", Exception(reported))
        assertTrue(AmeliaGeoUpdater.isGeoError(AmeliaGeoUpdater.errorText(wrapped)))
    }

    // ---- built-in bases and one more start --------------------------------------------------

    private class Run(private vararg val outcomes: Exception?) {
        var starts = 0
        var restores = 0
        fun start(): String {
            val o = outcomes.getOrNull(starts++)
            if (o != null) throw o
            return "started"
        }
    }

    @Test
    fun startThatWorksTouchesNothing() {
        val r = Run(null)
        assertEquals("started", AmeliaGeoUpdater.withGeoRecovery({ r.start() }, { r.restores++; true }))
        assertEquals(1, r.starts)
        assertEquals(0, r.restores)
    }

    @Test
    fun geoErrorRestoresBuiltInAndStartsOnceMore() {
        val r = Run(Exception(reported), null)
        // recovered: no exception reaches the caller, so no core_start crash report
        assertEquals("started", AmeliaGeoUpdater.withGeoRecovery({ r.start() }, { r.restores++; true }))
        assertEquals(2, r.starts)
        assertEquals(1, r.restores)
    }

    @Test
    fun onlyOneMoreStartThenTheErrorGoesOnAsBefore() {
        val first = Exception(reported)
        val second = Exception(reported)
        val r = Run(first, second, null)
        val e = assertThrows(Exception::class.java) { AmeliaGeoUpdater.withGeoRecovery({ r.start() }, { r.restores++; true }) }
        assertSame(second, e)
        assertSame(first, e.suppressed.single())
        assertEquals(2, r.starts)
        assertEquals(1, r.restores)
    }

    @Test
    fun otherErrorsAndFailedRestoreAreNotRetried() {
        val other = Exception("config error: failed to build outbound config")
        val r = Run(other, null)
        assertSame(other, assertThrows(Exception::class.java) { AmeliaGeoUpdater.withGeoRecovery({ r.start() }, { r.restores++; true }) })
        assertEquals(1, r.starts)
        assertEquals(0, r.restores)

        val geo = Exception(reported)
        val r2 = Run(geo, null)
        assertSame(geo, assertThrows(Exception::class.java) { AmeliaGeoUpdater.withGeoRecovery({ r2.start() }, { r2.restores++; false }) })
        assertEquals(1, r2.starts)
        assertEquals(1, r2.restores)
    }

    @Test
    fun everyCoreStartGoesThroughTheRecovery() {
        val src = File(resDir(), "../java/com/v2ray/ang/core/CoreServiceManager.kt").readText()
        val launch = src.substringAfter("private fun launchCore(").substringBefore("private fun launchCoreOnce(")
        assertTrue(launch.contains("AmeliaGeoUpdater.withGeoRecovery("))
        assertTrue(launch.contains("launchCoreOnce("))
        assertTrue(launch.contains("AmeliaGeoUpdater.restoreBuiltIn("))
        // the crash report stays in the caller: only an error that survived the recovery gets there
        assertFalse(launch.contains("AmeliaCrash"))
        assertEquals(1, Regex("""launchCoreOnce\(""").findAll(src).count() - 1, "one definition, one call")
    }

    @Test
    fun journalTextAndStartAndDailyChecks() {
        val strings = File(resDir(), "values/amelia_strings.xml").readText()
        assertTrue(strings.contains("<string name=\"amelia_journal_geo_restored\">Гео-база была повреждена — восстановлена встроенная</string>"))
        val activity = File(resDir(), "../java/com/v2ray/ang/amelia/AmeliaActivity.kt").readText()
        assertTrue(activity.contains("AmeliaGeoUpdater.repairIfDamaged(applicationContext)"))
        val worker = File(resDir(), "../java/com/v2ray/ang/amelia/AmeliaGeoUpdater.kt").readText().substringAfter("override suspend fun doWork()")
        assertTrue(worker.contains("repairIfDamaged(applicationContext)"))
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("V2rayNG/app/src/main/res")).first { it.isDirectory }
}
