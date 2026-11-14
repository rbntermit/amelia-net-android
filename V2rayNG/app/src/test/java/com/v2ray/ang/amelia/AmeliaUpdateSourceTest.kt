package com.v2ray.ang.amelia

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AmeliaUpdateSourceTest {
    @Test
    fun externalApkSourcesNeverEnterTheDownloadPlan() {
        listOf(
            "https://github.com/rbntermit/amelia-net-android/releases/download/v1.11.0/a.apk",
            "https://release-assets.githubusercontent.com/a.apk",
            "https://ameliajustamelia.com.evil.example/a.apk",
            "http://sub.ameliajustamelia.com:9443/app/a.apk",
        ).forEach { url ->
            assertFalse(AmeliaUpdater.isAllowedUrl(url))
            assertTrue(AmeliaUpdater.downloadPlan(url, null, true).isEmpty())
            assertTrue(AmeliaUpdater.downloadPlan(url, null, false).isEmpty())
        }
    }

    @Test
    fun aRedirectOutsideAmeliaIsRejectedBeforeItsHttpRequest() {
        val request = Request.Builder().url("https://release-assets.githubusercontent.com/a.apk").build()
        val chain = mock<Interceptor.Chain>()
        whenever(chain.request()).thenReturn(request)
        assertThrows(java.io.IOException::class.java) { AmeliaUpdater.ownApkResponse(chain) }
        verify(chain, never()).proceed(any())
    }

    @Test
    fun anAmeliaMirrorStillUsesTheExistingResponseAndRangeRequest() {
        val request = Request.Builder().url("https://msk2.ameliajustamelia.com:9443/app/a.apk")
            .header("Range", "bytes=1024-").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(206).message("Partial Content").body("apk-part".toResponseBody()).build()
        val chain = mock<Interceptor.Chain>()
        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(request)).thenReturn(response)
        assertSame(response, AmeliaUpdater.ownApkResponse(chain))
        verify(chain).proceed(request)
        assertEquals("bytes=1024-", request.header("Range"))
        response.close()
    }

    @Test
    fun thePreviousReleasedManifestStillSelectsOurArmApkAndUniversalFallback() {
        val sha = "a".repeat(64)
        val version = AmeliaUpdater.parse("""{
            "versionCode":40,"versionName":"1.10.1",
            "url":"https://sub.ameliajustamelia.com:9443/app/AmeliaNet_1.10.1_universal.apk",
            "sha256":"$sha","size":75000000,"notes":"Обновление","minVersionCode":1,
            "abis":{"arm64-v8a":{"file":"AmeliaNet_1.10.1_arm64-v8a.apk","sha256":"$sha","size":31000000}}
        }""")
        assertNotNull(version)
        val base = AmeliaConfig.SUB_BASE_URLS.first()
        val choices = AmeliaUpdater.apkChoices(version!!, listOf("arm64-v8a"), base)
        assertEquals("$base/app/AmeliaNet_1.10.1_arm64-v8a.apk", choices.first().url)
        assertEquals(2, choices.size)
        assertTrue(choices.all { AmeliaUpdater.isAllowedUrl(it.url) })
        assertEquals(1, version.minVersionCode)
        assertEquals(sha, choices.first().sha256)
    }
}
