package com.v2ray.ang.amelia

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import okhttp3.OkHttpClient

/**
 * Owns the key and the single Amelia.Net subscription. Data lives in the upstream stores
 * (MmkvManager), so the VPN service, tile and widget keep working unchanged.
 * All functions do disk or network I/O: call them off the main thread.
 */
object AmeliaManager {

    enum class Outcome { OK, INVALID_FORMAT, UNKNOWN_KEY, NETWORK_ERROR, EMPTY }

    data class Location(val guid: String, val name: String)

    fun getKey(): String? = MmkvManager.decodeSettingsString(AmeliaConfig.PREF_KEY)?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = getKey() != null

    fun getSubInfo(): AmeliaSubInfo? =
        MmkvManager.decodeSettingsString(AmeliaConfig.PREF_SUB_INFO)
            ?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, AmeliaSubInfo::class.java) }

    fun getLocations(): List<Location> =
        MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { Location(guid, it.remarks) }
        }

    fun getSelectedGuid(): String? = MmkvManager.getSelectServer()

    /**
     * Last known check of each location (AmeliaChecks, any age; the screen says how old):
     * ms, or -1 for no answer; never checked ones are absent.
     */
    fun getPings(): Map<String, Long> {
        val known = AmeliaChecks.load().entries
        return MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.remarks?.let { known[it] }?.let { guid to it.ms }
        }.toMap()
    }

    fun select(guid: String) {
        MmkvManager.setSelectServer(guid)
        // 1.5.8: the widget shows the chosen location also while the VPN is off
        AmeliaWidget.requestUpdate(AngApplication.application)
    }

    /** Checks the key against the server and, on success, makes it the active one. */
    fun activate(rawKey: String?): Outcome {
        val key = AmeliaKey.normalize(rawKey) ?: return Outcome.INVALID_FORMAT
        return load(key, preferredBaseUrl = AmeliaHttp.lastBase(), saveKeyOnSuccess = true)
    }

    @Volatile
    private var routingChanged = false

    /**
     * 1.5.2: the last subscription load changed the routing rules (once). The caller rebuilds
     * a running tunnel: the rules are part of the core config written at VPN start.
     */
    fun takeRoutingChanged(): Boolean = routingChanged.also { routingChanged = false }

    /** Re-downloads the subscription for the saved key. */
    fun refresh(): Outcome {
        val key = getKey() ?: return Outcome.INVALID_FORMAT
        return load(key, AmeliaHttp.lastBase(), saveKeyOnSuccess = false)
    }

    /**
     * 1.6.1, blocking: re-downloads the subscription only from msk2, then msk1, over [direct] (the
     * phone's own network), within [timeoutMs] in all (AmeliaResubscribe). Imported as usual.
     */
    fun refreshDirect(direct: OkHttpClient, timeoutMs: Long): Outcome {
        val key = getKey() ?: return Outcome.INVALID_FORMAT
        return load(key, saveKeyOnSuccess = false) { AmeliaSubscriptionClient.fetchDirect(key, direct, timeoutMs) }
    }

    private const val PREF_SUB_SOURCE = "amelia_sub_source"

    /** Address and route (TUNNEL / DIRECT) of the last successful subscription update. */
    fun lastSubscriptionSource(): Pair<String, AmeliaHttp.Route>? {
        val parts = MmkvManager.decodeSettingsString(PREF_SUB_SOURCE)?.split(' ') ?: return null
        val route = parts.getOrNull(1)?.let { runCatching { AmeliaHttp.Route.valueOf(it) }.getOrNull() } ?: return null
        return parts[0] to route
    }

    /** Epoch millis of the last successful subscription update, 0 if never. */
    fun lastSubscriptionUpdate(): Long =
        MmkvManager.decodeSubscription(AmeliaConfig.SUBSCRIPTION_ID)?.lastUpdated?.takeIf { it > 0 } ?: 0L

    /** Forgets the key and every location. The caller stops the VPN first. */
    fun logout() {
        SubscriptionUpdater.cancelOne(subId = AmeliaConfig.SUBSCRIPTION_ID)
        MmkvManager.removeServerViaSubid(AmeliaConfig.SUBSCRIPTION_ID)
        MmkvManager.removeSubscription(AmeliaConfig.SUBSCRIPTION_ID)
        MmkvManager.encodeSettings(AmeliaConfig.PREF_KEY, "")
        MmkvManager.encodeSettings(AmeliaConfig.PREF_SUB_INFO, "")
        MmkvManager.encodeSettings(PREF_SUB_SOURCE, "")
        AmeliaRouteProfile.clear()
        AmeliaWidget.requestUpdate(AngApplication.application)
    }

    private fun load(key: String, preferredBaseUrl: String?, saveKeyOnSuccess: Boolean): Outcome =
        load(key, saveKeyOnSuccess) { AmeliaSubscriptionClient.fetch(key, preferredBaseUrl) }

    private fun load(key: String, saveKeyOnSuccess: Boolean, fetch: () -> AmeliaSubscriptionClient.Result): Outcome {
        val result = fetch()
        if (result !is AmeliaSubscriptionClient.Result.Ok) {
            return if (result is AmeliaSubscriptionClient.Result.UnknownKey) Outcome.UNKNOWN_KEY else Outcome.NETWORK_ERROR
        }

        val info = result.info
        val hours = info.updateIntervalHours ?: AmeliaConfig.DEFAULT_UPDATE_INTERVAL_HOURS
        // 1.7.0: upstream's periodic worker is off (autoUpdate = false; syncOne below cancels its task).
        // It downloaded the bare URL without the end date, routing or spare addresses. In the
        // background only AmeliaUpdateWorker refreshes, through this same function (refresh()).
        val sub = (MmkvManager.decodeSubscription(AmeliaConfig.SUBSCRIPTION_ID) ?: SubscriptionItem()).apply {
            remarks = info.title ?: AmeliaConfig.SUBSCRIPTION_REMARKS
            url = AmeliaKey.subscriptionUrl(result.baseUrl, key)
            enabled = true
            autoUpdate = false
            updateInterval = hours * 60L
            userAgent = AmeliaSubscriptionClient.userAgent
        }
        MmkvManager.encodeSubscription(AmeliaConfig.SUBSCRIPTION_ID, sub)

        // Import assigns new GUIDs to every location, so remember the choice by name.
        val previousName = getSelectedGuid()?.let { MmkvManager.decodeServerConfig(it)?.remarks }
        val count = AngConfigManager.importSubscriptionContent(result.body, AmeliaConfig.SUBSCRIPTION_ID)
        if (count <= 0) {
            LogUtil.w(AppConfig.TAG, "Amelia: subscription has no usable locations")
            if (saveKeyOnSuccess && !hasKey()) {
                MmkvManager.removeSubscription(AmeliaConfig.SUBSCRIPTION_ID)
            }
            return Outcome.EMPTY
        }

        sub.lastUpdated = System.currentTimeMillis()
        MmkvManager.encodeSubscription(AmeliaConfig.SUBSCRIPTION_ID, sub)
        MmkvManager.encodeSettings(AmeliaConfig.PREF_SUB_INFO, JsonUtil.toJson(info))
        MmkvManager.encodeSettings(PREF_SUB_SOURCE, "${result.baseUrl} ${result.route.name}")
        // 1.5.2: site lists of the subscription's routing profile; a missing or broken header keeps the rules
        if (AmeliaRouteProfile.update(info.routing) { Utils.decode(it).ifEmpty { null } }) {
            LogUtil.i(AppConfig.TAG, "Amelia: routing profile from the subscription changed, rules rewritten")
            AmeliaRouting.apply(AngApplication.application)
            routingChanged = true
        }
        if (saveKeyOnSuccess) {
            MmkvManager.encodeSettings(AmeliaConfig.PREF_KEY, key)
        }
        ensureSelection(previousName, info)
        SubscriptionUpdater.syncOne(subId = AmeliaConfig.SUBSCRIPTION_ID)
        // 1.5.8: a renewal moves the end date ("Подписка до …", "Продлить" on the widget)
        AmeliaWidget.requestUpdate(AngApplication.application)
        return Outcome.OK
    }

    /**
     * Re-selects the location with the same name as before, otherwise the first regular one
     * (never a reserve "только Telegram" one while there are others). After a fallback to a
     * reserve location, a renewed subscription moves the choice back to a regular location.
     */
    private fun ensureSelection(previousName: String?, info: AmeliaSubInfo) {
        val locations = getLocations()
        if (locations.isEmpty()) return
        val selected = getSelectedGuid()
        val currentName = locations.firstOrNull { it.guid == selected }?.name ?: previousName
        if (AmeliaReserveState.isAuto() && AmeliaReserve.isReserve(currentName) &&
            !AmeliaFormat.isExpired(info, System.currentTimeMillis())
        ) {
            AmeliaReserveState.setAuto(false)
            AmeliaManual.markAutomatic()
            AmeliaReserve.firstChoice(locations)?.let { select(it.guid) }
            return
        }
        if (locations.any { it.guid == selected }) return
        val match = previousName?.let { name -> locations.firstOrNull { it.name == name } }
        (match ?: AmeliaReserve.firstChoice(locations))?.let { select(it.guid) }
    }
}
