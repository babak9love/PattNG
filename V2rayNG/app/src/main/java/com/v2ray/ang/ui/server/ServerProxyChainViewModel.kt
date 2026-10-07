package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome

/** PattNG: the save and the delete of the proxy chain editor, see [ProfileEditorViewModel]. */
class ServerProxyChainViewModel(
    application: Application,
    source: ProfileEditorSource,
    guid: String,
    subscriptionId: String?,
    serviceRunning: Boolean = false,
) : ProfileEditorViewModel(application, source, guid, subscriptionId, serviceRunning) {

    /**
     * Saves the chain as [remarks] with [members], the names of its profiles in its order. Every member has to be
     * chosen, and there have to be two. They are found by their names as the chain finds its hops when it runs: a name
     * no profile has, as after a rename or a delete, or several have, or one whose profile has no server address, is
     * told rather than saved, and so is a second Aether member, see [proxyChainProblem].
     */
    fun save(remarks: String, members: List<String>) = launchSave {
        if (remarks.isBlank()) return@launchSave null
        val chainMembers = members.map { it.trim() }.filter { it.isNotEmpty() }
        if (chainMembers.size != members.size) return@launchSave EditorOutcome.Refused(R.string.server_proxy_chain_members_unselected)
        if (chainMembers.size < 2) return@launchSave EditorOutcome.Refused(R.string.server_proxy_chain_members_insufficient)

        when (val problem = source.withProfileNames(CoreConfigContextBuilder::takesAsHop) { proxyChainProblem(chainMembers, it) }) {
            is ProxyChainProblem.Unresolved -> return@launchSave EditorOutcome.Refused(problem.message, listOf(problem.name))
            // An Aether member can stand anywhere in the chain, but one core runs, so there can be one.
            ProxyChainProblem.SecondAether -> return@launchSave EditorOutcome.Refused(R.string.aether_chain_one_profile)
            null -> Unit
        }

        store(EConfigType.PROXYCHAIN) { config ->
            config.remarks = remarks.trim()
            config.proxyChainProfiles = ProfileItem.proxyChainProfilesOf(chainMembers)
            config.description = chainMembers.joinToString(" -> ")
        }
    }
}
