package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PattNG: how an editor finds the profiles it names: by their names, as they are found when they are used, see [ByName]. */
interface ProfileNameSource {
    /**
     * What [check] gives, run off the main thread with a `find` that tells what a name finds among the profiles [takes]
     * lets through, see [SettingsManager.findServerViaRemarks].
     */
    suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T
}

/** PattNG: [ProfileNameSource.withProfileNames] over the profiles stored on this device. */
internal suspend fun <T> withStoredProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
    withContext(Dispatchers.IO) { check { SettingsManager.findServerViaRemarks(it, takes) } }

/** PattNG: where the proxy chain and the policy group editors find the profiles they name and store, or delete, their own. */
interface ProfileEditorSource : ProfileNameSource {
    /**
     * Stores the profile [guid] names with [edit] made on it as stored then, or, when [guid] is blank or names none any
     * more, a new profile of [type] with [edit] made on it; gives the guid it is stored as.
     */
    suspend fun saveProfile(guid: String, type: EConfigType, edit: (ProfileItem) -> Unit): String

    /** Whether [guid] names the profile selected, the one the app runs on. */
    suspend fun isSelected(guid: String): Boolean

    /** Deletes the profile [guid] names. */
    suspend fun deleteProfile(guid: String)
}

/**
 * PattNG: [ProfileEditorSource] over [MmkvManager], which owns the profiles: it only moves their reads and writes off
 * the main thread, and lets the editors' view models be tested without it.
 */
class ProfileEditorRepository : ProfileEditorSource {

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        withStoredProfileNames(takes, check)

    override suspend fun saveProfile(guid: String, type: EConfigType, edit: (ProfileItem) -> Unit): String =
        withContext(Dispatchers.IO) {
            val config = MmkvManager.decodeServerConfig(guid) ?: ProfileItem.create(type)
            edit(config)
            MmkvManager.encodeServerConfig(guid, config)
        }

    override suspend fun isSelected(guid: String): Boolean =
        withContext(Dispatchers.IO) { MmkvManager.getSelectServer() == guid }

    override suspend fun deleteProfile(guid: String) {
        withContext(Dispatchers.IO) { MmkvManager.removeServer(guid) }
    }
}
