package com.v2ray.ang.amelia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Daemon → UI state broadcasts (same protocol as upstream MainRepository).
 * Registering asks the daemon to report its current state right away.
 */
class AmeliaServiceEvents(private val context: Context) : Closeable {

    sealed class Event {
        data object Running : Event()
        data object Stopped : Event()
        data class StartFailed(val message: String?) : Event()
        /** Result of [requestTunnelProbe]: delay in ms, or -1 when the probe failed or was cancelled. */
        data class Probe(val requestId: String, val delayMs: Long) : Event()
    }

    /**
     * Asks the running core to fetch Google through the tunnel (upstream MSG_MEASURE_DELAY).
     * The answer arrives as [Event.Probe] with the same request id.
     */
    fun requestTunnelProbe(requestId: String) {
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_MEASURE_DELAY, requestId) { handled ->
            if (!handled) channel.trySend(Event.Probe(requestId, -1L))
        }
    }

    private val closed = AtomicBoolean(false)
    private val channel = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = channel.receiveAsFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val event = when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> Event.Running
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> Event.Stopped
                AppConfig.MSG_STATE_START_FAILURE -> Event.StartFailed(intent.getStringExtra("content"))
                AppConfig.MSG_MEASURE_DELAY_RESULT -> Event.Probe(
                    intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty(),
                    intent.serializable<ConnectionTestResult>("content")?.delayMillis ?: -1L,
                )
                AppConfig.MSG_MEASURE_DELAY_CANCEL ->
                    Event.Probe(intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty(), -1L)
                else -> null
            }
            event?.let { channel.trySend(it) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags(),
        )
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { MessageHelper.sendMsg2Service(context, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { LogUtil.e(AppConfig.TAG, "Amelia: failed to unregister service receiver", it) }
        channel.close()
    }
}
