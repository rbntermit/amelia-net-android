package com.v2ray.ang.amelia

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

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

    /** Last "ping all" results kept by upstream: ms, or -1 for no answer; untested ones are absent. */
    fun getPings(): Map<String, Long> =
        MmkvManager.decodeServerList(AmeliaConfig.SUBSCRIPTION_ID).mapNotNull { guid ->
            MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis?.takeIf { it != 0L }?.let { guid to it }
        }.toMap()

    fun select(guid: String) = MmkvManager.setSelectServer(guid)

    /** Checks the key against the server and, on success, makes it the active one. */
    fun activate(rawKey: String?): Outcome {
        val key = AmeliaKey.normalize(rawKey) ?: return Outcome.INVALID_FORMAT
        return load(key, preferredBaseUrl = null, saveKeyOnSuccess = true)
    }

    /** Re-downloads the subscription for the saved key. */
    fun refresh(): Outcome {
        val key = getKey() ?: return Outcome.INVALID_FORMAT
        val current = MmkvManager.decodeSubscription(AmeliaConfig.SUBSCRIPTION_ID)?.url
        val preferred = AmeliaConfig.SUB_BASE_URLS.firstOrNull { current?.startsWith(it) == true }
        return load(key, preferred, saveKeyOnSuccess = false)
    }

    /** Forgets the key and every location. The caller stops the VPN first. */
    fun logout() {
        SubscriptionUpdater.cancelOne(subId = AmeliaConfig.SUBSCRIPTION_ID)
        MmkvManager.removeServerViaSubid(AmeliaConfig.SUBSCRIPTION_ID)
        MmkvManager.removeSubscription(AmeliaConfig.SUBSCRIPTION_ID)
        MmkvManager.encodeSettings(AmeliaConfig.PREF_KEY, "")
        MmkvManager.encodeSettings(AmeliaConfig.PREF_SUB_INFO, "")
    }

    private fun load(key: String, preferredBaseUrl: String?, saveKeyOnSuccess: Boolean): Outcome {
        val result = AmeliaSubscriptionClient.fetch(key, preferredBaseUrl)
        if (result !is AmeliaSubscriptionClient.Result.Ok) {
            return if (result is AmeliaSubscriptionClient.Result.UnknownKey) Outcome.UNKNOWN_KEY else Outcome.NETWORK_ERROR
        }

        val info = result.info
        val hours = info.updateIntervalHours ?: AmeliaConfig.DEFAULT_UPDATE_INTERVAL_HOURS
        // Upstream's periodic worker re-fetches this URL in the background (no headers, no fallback);
        // the app refreshes with headers and fallback on every start.
        val sub = (MmkvManager.decodeSubscription(AmeliaConfig.SUBSCRIPTION_ID) ?: SubscriptionItem()).apply {
            remarks = info.title ?: AmeliaConfig.SUBSCRIPTION_REMARKS
            url = AmeliaKey.subscriptionUrl(result.baseUrl, key)
            enabled = true
            autoUpdate = true
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
        if (saveKeyOnSuccess) {
            MmkvManager.encodeSettings(AmeliaConfig.PREF_KEY, key)
        }
        ensureSelection(previousName)
        SubscriptionUpdater.syncOne(subId = AmeliaConfig.SUBSCRIPTION_ID)
        return Outcome.OK
    }

    /** Re-selects the location with the same name as before, otherwise the first one. */
    private fun ensureSelection(previousName: String?) {
        val locations = getLocations()
        if (locations.isEmpty()) return
        if (locations.any { it.guid == getSelectedGuid() }) return
        val match = previousName?.let { name -> locations.firstOrNull { it.name == name } }
        select((match ?: locations.first()).guid)
    }
}
