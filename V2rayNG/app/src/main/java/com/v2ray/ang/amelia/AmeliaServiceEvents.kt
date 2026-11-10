package com.v2ray.ang.amelia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
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
        /** Connection health from the daemon watchdog (see AmeliaDaemon). */
        data class Health(val health: AmeliaDaemon.Health) : Event()
        /** Session traffic from the daemon, every few seconds while the screen is on. */
        data class Traffic(val traffic: AmeliaDaemon.Traffic) : Event()
        /** "Ping all": one location measured (delay in ms, -1 = no answer). */
        data class Ping(
            val requestId: String,
            val guid: String,
            val delayMs: Long,
            /** See AmeliaPingStage; null from an older test service. */
            val stage: String? = null,
            val error: String? = null,
        ) : Event()
        /** "Ping all" finished or was cancelled. */
        data class PingDone(val requestId: String) : Event()
        /** The daemon stored new check results (AmeliaChecks). */
        data object ChecksUpdated : Event()
    }

    /**
     * Measures every location of [subscriptionId] through its own core instance
     * (upstream CoreTestService, the same real-delay test v2rayNG uses). Works with the VPN on or off.
     */
    fun requestPingAll(requestId: String, subscriptionId: String) {
        MessageHelper.sendMsg2TestService(
            context,
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_START, subscriptionId = subscriptionId),
            requestId,
        )
    }

    fun cancelPingAll() {
        MessageHelper.sendMsg2TestService(context, TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL))
    }

    /** "Не работает?": the daemon checks right away and switches locations if needed. */
    fun requestRecheck() {
        MessageHelper.sendMsg2Service(context, AmeliaConfig.MSG_RECHECK, "")
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
                AmeliaConfig.MSG_HEALTH ->
                    intent.serializable<AmeliaDaemon.Health>("content")?.let { Event.Health(it) }
                AmeliaConfig.MSG_TRAFFIC ->
                    intent.serializable<AmeliaDaemon.Traffic>("content")?.let { Event.Traffic(it) }
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> intent.serializable<RealPingResult>("content")?.let {
                    Event.Ping(
                        intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty(), it.guid, it.delayMillis, it.stage, it.error,
                    )
                }
                AmeliaConfig.MSG_CHECKS -> Event.ChecksUpdated
                AppConfig.MSG_MEASURE_CONFIG_FINISH, AppConfig.MSG_MEASURE_CONFIG_CANCEL ->
                    Event.PingDone(intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty())
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
