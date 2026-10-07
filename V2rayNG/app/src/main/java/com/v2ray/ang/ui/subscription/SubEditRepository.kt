package com.v2ray.ang.ui.subscription

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.ui.server.ProfileNameSource
import com.v2ray.ang.ui.server.storedProfileNames
import com.v2ray.ang.ui.server.withStoredProfileNames
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PattNG: where the subscription editor reads what it opens on, finds the profiles it names and stores, or deletes, its
 * subscription.
 */
interface SubEditSource : ProfileNameSource {
    /** The subscription [subId] names, read off the main thread; null when it names none. */
    suspend fun loadSubscription(subId: String): SubscriptionItem?

    /** The names of the stored profiles but those of [excluded] types, read off the main thread, see [storedProfileNames]. */
    suspend fun profileNames(excluded: Set<EConfigType>): List<String>

    /** Whether a delete is confirmed first, as the settings have it, read off the main thread. */
    suspend fun confirmsRemove(): Boolean

    /**
     * Stores the subscription [subId] names with [edit] made on it as stored then, so that what a background update
     * wrote meanwhile, as its update time, stays, or, when [subId] is blank, a new one; schedules its updates as it
     * has them now; gives the key it is stored as, or null, with nothing stored, when the storage refused it.
     */
    suspend fun saveSubscription(subId: String, edit: (SubscriptionItem) -> Unit): String?

    /** Deletes the subscription [subId] names, with its profiles. False, with nothing deleted, when the storage refused it. */
    suspend fun deleteSubscription(subId: String): Boolean
}

/**
 * PattNG: [SubEditSource] over [MmkvManager] and [SettingsManager], which own the subscriptions: it only moves their
 * reads and writes off the main thread, and lets the editor's view model be tested without them.
 */
class SubEditRepository : SubEditSource {

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        withStoredProfileNames(takes, check)

    override suspend fun loadSubscription(subId: String): SubscriptionItem? =
        withContext(Dispatchers.IO) { MmkvManager.decodeSubscription(subId) }

    override suspend fun profileNames(excluded: Set<EConfigType>): List<String> = storedProfileNames(excluded)

    override suspend fun confirmsRemove(): Boolean =
        withContext(Dispatchers.IO) { MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE, false) }

    // A refusal is logged by tryEncodeSubscription; its updates are scheduled only once it is stored.
    override suspend fun saveSubscription(subId: String, edit: (SubscriptionItem) -> Unit): String? =
        withContext(Dispatchers.IO) {
            // A new subscription gets its key here, so that its updates are scheduled under it.
            val key = subId.ifBlank { Utils.getUuid() }
            val subItem = MmkvManager.decodeSubscription(key) ?: SubscriptionItem()
            edit(subItem)
            MmkvManager.tryEncodeSubscription(key, subItem) ?: return@withContext null
            SubscriptionUpdater.syncOne(subId = key)
            SettingsChangeManager.makeSetupGroupTab()
            key
        }

    // A refusal is logged by MmkvManager.tryRemoveSubscription.
    override suspend fun deleteSubscription(subId: String): Boolean =
        withContext(Dispatchers.IO) {
            SettingsManager.tryRemoveSubscriptionWithDefault(subId).also { removed ->
                if (removed) SettingsChangeManager.makeSetupGroupTab()
            }
        }
}
