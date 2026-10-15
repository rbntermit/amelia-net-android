package com.v2ray.ang.amelia

/**
 * Where a server check stopped, as sent in /app/report and /app/probe:
 * - [CONNECT]: no connection to the server at all (TCP handshake or QUIC handshake failed);
 * - [DATA]: the connection opens, but the test request through it does not come back
 *   (typical for DPI that lets the handshake through and then cuts the session);
 * - [OK]: the test request through the server worked.
 *
 * TCP servers get a plain TCP connect first (upstream real ping does that too), so for them
 * the split is exact. For UDP (hysteria2) only the core's error text is available.
 */
object AmeliaPingStage {
    const val CONNECT = "connect"
    const val DATA = "data"
    const val OK = "ok"

    const val MAX_ERROR = 200
    const val TCP_FAILED = "tcp connect failed"

    /** Error texts of Go / Xray / quic-go that mean "never got connected". */
    private val CONNECT_MARKERS = listOf(
        "dial ", "connection refused", "network is unreachable", "no route to host",
        "no such host", "lookup ", "no recent network activity", "handshake did not complete",
        "i/o timeout", AmeliaCheck.TIMEOUT_ERROR,
    )

    /**
     * @param delayMs core result, -1 on failure
     * @param tcpOk true when a direct TCP connect to the server succeeded, null when not checked (UDP)
     * @param error the core's error text
     */
    fun classify(delayMs: Long, tcpOk: Boolean?, error: String?): String {
        if (delayMs >= 0) return OK
        if (tcpOk == true) return DATA
        val e = error?.lowercase() ?: return DATA
        return if (CONNECT_MARKERS.any { it in e }) CONNECT else DATA
    }
}
