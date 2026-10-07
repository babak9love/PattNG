package com.v2ray.ang.ui.subscription

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.base.EditorViewModel
import com.v2ray.ang.ui.server.ProxyChainProblem
import com.v2ray.ang.ui.server.proxyChainProblem

/**
 * PattNG: the save and the delete of the subscription editor, see [EditorViewModel]. The subscription is stored as
 * [subId]: the one the screen was opened on, or none for a new one until its first save stores it, which a later save
 * writes over.
 */
class SubEditViewModel(
    application: Application,
    private val source: SubEditSource,
    private var subId: String,
) : EditorViewModel(application) {

    /**
     * Saves the subscription with [edits], the edits of the screen read at the tap, made on the subscription as stored
     * when it is written. The previous and the next profile are found by their names, as the chain finds them when it
     * runs: a name no profile has, as after a rename or a delete, or several have, or one whose profile has no server
     * address, is told rather than saved, see [proxyChainProblem].
     */
    fun save(edits: (SubscriptionItem) -> Unit) = launchSave {
        val edited = SubscriptionItem().also(edits)
        // The next profile first, then the previous one, as the chain finds them.
        val neighbors = listOfNotNull(edited.nextProfile, edited.prevProfile).map { it.trim() }.filter { it.isNotEmpty() }
        when (val problem = source.withProfileNames(CoreConfigContextBuilder::takesAsHop) { proxyChainProblem(neighbors, it) }) {
            is ProxyChainProblem.Unresolved -> return@launchSave EditorOutcome.Refused(problem.message, listOf(problem.name))
            // The previous and the next profile chain every profile of the subscription. Either can be Aether, but not
            // both: one core runs, so a chain can have one Aether profile.
            ProxyChainProblem.SecondAether -> return@launchSave EditorOutcome.Refused(R.string.aether_chain_one_profile)
            null -> Unit
        }

        subId = source.saveSubscription(subId, edits)
        EditorOutcome.Saved(subId)
    }

    /** Deletes the subscription, see [EditorViewModel.launchDelete]; a new one, never stored, has none to delete. */
    fun delete() {
        val key = subId.takeIf { it.isNotEmpty() } ?: return
        launchDelete {
            source.deleteSubscription(key)
            EditorOutcome.Deleted
        }
    }
}
