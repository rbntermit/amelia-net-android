package com.v2ray.ang.amelia

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.security.MessageDigest

/**
 * 1.4.0: before installing, the downloaded APK must be our package, signed with our key and the
 * version version.json promised; otherwise the system would refuse it with an unclear error.
 */
object AmeliaApkCheck {

    enum class Verdict { OK, BAD_SIGNATURE, BAD_VERSION, UNREADABLE }

    data class Result(val verdict: Verdict, val versionName: String? = null)

    /** Pure: at least one signer of the APK is one of ours (rotation keeps the old one listed). */
    fun sameSigner(installed: Set<String>, apk: Set<String>): Boolean = apk.isNotEmpty() && apk.any { it in installed }

    /** Pure. */
    fun judge(pkg: String?, code: Long, name: String?, signerOk: Boolean, expectedCode: Int, ourPkg: String): Result = when {
        pkg != ourPkg -> Result(Verdict.BAD_SIGNATURE, name)
        !signerOk -> Result(Verdict.BAD_SIGNATURE, name)
        expectedCode > 0 && code != expectedCode.toLong() -> Result(Verdict.BAD_VERSION, name)
        code <= BuildConfig.VERSION_CODE -> Result(Verdict.BAD_VERSION, name)
        else -> Result(Verdict.OK, name)
    }

    /** Blocking. [expectedCode] from version.json (0 = only "newer than installed"). */
    fun check(context: Context, apk: File, expectedCode: Int): Result {
        val pm = context.packageManager
        return try {
            val archive = archiveInfo(pm, apk.absolutePath) ?: return Result(Verdict.UNREADABLE)
            val installed = installedInfo(pm, context.packageName)
            val ok = sameSigner(hashes(signers(installed)), hashes(signers(archive)))
            judge(archive.packageName, versionCode(archive), archive.versionName, ok, expectedCode, context.packageName)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Amelia: cannot read the downloaded update", e)
            Result(Verdict.UNREADABLE)
        }
    }

    private fun hashes(sigs: Array<Signature>?): Set<String> = sigs.orEmpty().map { s ->
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else legacyVersionCode(p)

    /** PackageInfo.versionCode is deprecated since API 28 (longVersionCode). Remove when minSdk reaches 28. */
    @Suppress("DEPRECATION")
    private fun legacyVersionCode(p: PackageInfo): Long = p.versionCode.toLong()

    private fun signers(p: PackageInfo): Array<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
                ?: legacySignatures(p)
        } else {
            legacySignatures(p)
        }

    /** PackageInfo.signatures and GET_SIGNATURES are deprecated since API 28. Remove when minSdk reaches 28. */
    @Suppress("DEPRECATION")
    private fun legacySignatures(p: PackageInfo): Array<Signature>? = p.signatures

    @Suppress("DEPRECATION") // GET_SIGNATURES only below API 28, see legacySignatures
    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags().toLong()))
        } else {
            legacyArchiveInfo(pm, path)
        }

    /** The int-flags overload is deprecated since API 33. Remove when minSdk reaches 33. */
    @Suppress("DEPRECATION")
    private fun legacyArchiveInfo(pm: PackageManager, path: String): PackageInfo? = pm.getPackageArchiveInfo(path, flags())

    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags().toLong()))
        } else {
            legacyPackageInfo(pm, pkg)
        }

    /** The int-flags overload is deprecated since API 33. Remove when minSdk reaches 33. */
    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(pm: PackageManager, pkg: String): PackageInfo = pm.getPackageInfo(pkg, flags())
}
