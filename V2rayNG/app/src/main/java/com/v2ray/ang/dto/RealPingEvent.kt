package com.v2ray.ang.dto

sealed class RealPingEvent {

    /** Periodic progress update while the batch is still running. */
    data class Progress(val text: String) : RealPingEvent()

    /**
     * A single server result is available.
     * Amelia.Net: [stage] is "connect", "data" or "ok" (see AmeliaPingStage) and [error] the
     * core's error text, for the problem report and the blocking map.
     */
    data class Result(
        val guid: String,
        val delayMillis: Long,
        val stage: String? = null,
        val error: String? = null,
    ) : RealPingEvent()

    /** The entire batch has finished or been cancelled. */
    data class Finish(val status: String) : RealPingEvent()
}
