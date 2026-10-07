package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CompletableDeferred

/** The stored profiles an editor's test looks names up among, as [withStoredProfileNames] looks them up among the profiles of the device. */
internal class FakeProfileNames {
    val profiles = mutableListOf<ProfileItem>()

    /** The lookups made so far. */
    var lookups = 0

    /** When set, a lookup waits for it, as one off the main thread takes its time. */
    var gate: CompletableDeferred<Unit>? = null

    suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T {
        lookups++
        gate?.await()
        return check { name -> ByName.find(name, profiles.asSequence().filter(takes)) { it.remarks } }
    }

    /** Adds a profile of [type] named [name], with a server unless [server] is null. */
    fun add(name: String, type: EConfigType = EConfigType.VLESS, server: String? = "203.0.113.7") {
        profiles += ProfileItem.create(type).apply {
            remarks = name
            this.server = server
            serverPort = "443"
        }
    }
}

/** The profiles the proxy chain and the policy group editors store, in memory, with their names looked up in [names]. */
internal class FakeProfileEditorSource(val names: FakeProfileNames = FakeProfileNames()) : ProfileEditorSource {
    val stored = linkedMapOf<String, ProfileItem>()

    /** The guid each save was asked to store as, blank for a new profile. */
    val saves = mutableListOf<String>()

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        names.withProfileNames(takes, check)

    override suspend fun saveProfile(guid: String, type: EConfigType, edit: (ProfileItem) -> Unit): String {
        saves += guid
        val key = guid.ifBlank { "guid-${stored.size + 1}" }
        val config = stored[key] ?: ProfileItem.create(type)
        edit(config)
        stored[key] = config
        return key
    }
}
