package com.v2ray.ang.dto

import java.io.Serializable

/** One persisted delay-test result delivered from the task process to the UI. */
data class RealPingResult(
    val guid: String,
    val delayMillis: Long,
    /** Amelia.Net: "connect", "data" or "ok"; null from upstream code paths. */
    val stage: String? = null,
    /** Amelia.Net: the core's error text, at most 200 characters. */
    val error: String? = null,
) : Serializable
