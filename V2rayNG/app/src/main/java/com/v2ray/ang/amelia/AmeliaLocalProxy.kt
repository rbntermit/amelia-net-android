package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * 1.7.0: the core's local SOCKS/HTTP inbound (127.0.0.1) gets a random login, password and port,
 * made once and kept in MMKV (shared by all processes, so the daemon, the app, the workers and
 * tun2socks agree on them). Until 1.6.1 it listened on 127.0.0.1:10808 without a password: any app
 * on the phone, apps around the VPN included, could see the VPN was on and learn the server's
 * address through it.
 *
 * The values live in the upstream settings keys (PREF_SOCKS_PORT / USERNAME / PASSWORD), which
 * every reader already uses: CoreConfigManager (inbound accounts), TProxyService (tun2socks),
 * AmeliaHttp and HttpUtil (Proxy-Authorization). Speed tests run their own core with the same
 * login (AmeliaSpeed); location checks (AmeliaCheck, CoreTestService) run cores without inbounds.
 */
object AmeliaLocalProxy {

    const val PORT_MIN = 20_000
    const val PORT_MAX = 60_000
    /** Set once the random login, password and port were written (any process). */
    const val PREF_DONE = "amelia_local_proxy_v170"
    private const val SECRET_CHARS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val secure: Random by lazy { SecureRandom().asKotlinRandom() }

    data class Values(val port: Int, val user: String, val pass: String)

    // ---- pure (unit-tested) -------------------------------------------------------------------

    fun newPort(random: Random): Int = random.nextInt(PORT_MIN, PORT_MAX + 1)

    fun newSecret(random: Random, length: Int = 24): String =
        buildString { repeat(length) { append(SECRET_CHARS[random.nextInt(SECRET_CHARS.length)]) } }

    /**
     * What to store, or null when the stored values are complete. Before 1.7.0 there was no
     * login and the port was the fixed 10808 (or a value of an old upstream setting): all three
     * are made anew once, so an update from any earlier version gets them.
     */
    fun plan(done: Boolean, port: String?, user: String?, pass: String?, random: Random): Values? {
        val portOk = port?.toIntOrNull()?.let { it in PORT_MIN..PORT_MAX } == true
        if (done && portOk && !user.isNullOrBlank() && !pass.isNullOrBlank()) return null
        return Values(
            port = if (done && portOk) port!!.toInt() else newPort(random),
            user = user?.takeIf { done && it.isNotBlank() } ?: newSecret(random, 12),
            pass = pass?.takeIf { done && it.isNotBlank() } ?: newSecret(random),
        )
    }

    /** Another port than [busy] in the range (the stored one is taken by someone else). */
    fun otherPort(busy: Int, random: Random): Int {
        var p = newPort(random)
        while (p == busy) p = newPort(random)
        return p
    }

    /** A login for a short-lived inbound of its own (the speed test), not stored. */
    fun oneOffLogin(): Pair<String, String> = newSecret(secure, 12) to newSecret(secure)

    // ---- storage ----------------------------------------------------------------------------

    /** Every process at start (AngApplication): makes the values once, under the shared lock. */
    fun ensure() {
        try {
            MmkvManager.withSettingsLock {
                val v = plan(
                    MmkvManager.decodeSettingsBool(PREF_DONE),
                    MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT),
                    MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_USERNAME),
                    MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PASSWORD),
                    secure,
                ) ?: return@withSettingsLock
                MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, v.port.toString())
                MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, v.user)
                MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, v.pass)
                MmkvManager.encodeSettings(PREF_DONE, true)
                LogUtil.i(AppConfig.TAG, "Amelia: local proxy login and port created")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Amelia: local proxy values not created", e)
        }
    }

    /**
     * Daemon, blocking, before the core starts (not on an in-place reload): the stored port is
     * taken by another app → a new one is stored. Our own core that is still stopping may hold
     * it for a moment, so it is tried a few times first.
     */
    fun ensurePortFree() {
        val port = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT)?.toIntOrNull() ?: return
        repeat(3) { attempt ->
            if (isFree(port)) return
            if (attempt < 2) Thread.sleep(300)
        }
        val next = otherPort(port, secure)
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, next.toString())
        LogUtil.w(AppConfig.TAG, "Amelia: local proxy port was busy, moved to another one")
    }

    private fun isFree(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName(AppConfig.LOOPBACK), port)); true }
    } catch (_: Exception) {
        false
    }
}
