package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.base.EditorViewModel

/** PattNG: a subscription the policy group editor offers to draw members from: its [id], blank for all, and its [label]. */
data class PolicyGroupSubscription(val id: String, val label: String)

/**
 * PattNG: the subscriptions the policy group editor offers, [all] first, then [subscriptions], their keys beside their
 * names, under labels made unique, see [distinctLabels]: the list hands back the label picked, and two subscriptions
 * of the same name, or one named as [all] is, are two picks still.
 */
internal fun policyGroupSubscriptions(
    all: String,
    subscriptions: List<Pair<String, String>>,
    numbered: (String, Int) -> String,
): List<PolicyGroupSubscription> {
    val labels = distinctLabels(subscriptions.map { it.second }, setOf(all), numbered)
    return listOf(PolicyGroupSubscription("", all)) + subscriptions.mapIndexed { index, (id, _) -> PolicyGroupSubscription(id, labels[index]) }
}

/** PattNG: the subscription of these, as [policyGroupSubscriptions] offers them, whose key is [id]; all when none is, as for a subscription gone. */
internal fun List<PolicyGroupSubscription>.pick(id: String?): PolicyGroupSubscription = firstOrNull { it.id == id } ?: first()

/**
 * PattNG: what the policy group editor saves: its fields as the screen has them, the [type] and the [subscriptionId] as
 * picked, and the labels the screen shows for them, which make the group's description.
 */
data class PolicyGroupEdit(
    val remarks: String,
    val filter: String,
    val type: Int,
    val typeLabel: String,
    val subscriptionId: String?,
    val subscriptionLabel: String,
    val testOutbounds: Boolean,
    val fallbackTag: String,
)

/**
 * PattNG: the save of the policy group editor, see [EditorViewModel]. The group is stored as [guid]: the profile the
 * screen was opened on, or none for a new group until its first save stores one, which a later save writes over. A new
 * group goes into the subscription [subscriptionId], when the screen was opened in one.
 */
class ServerGroupViewModel(
    application: Application,
    private val source: ProfileEditorSource,
    private var guid: String,
    private val subscriptionId: String?,
) : EditorViewModel(application) {

    /**
     * Saves the group as [edit] has it. The fallback of a group that tests its members names a profile, and the name has
     * to find that one profile, as at the start: a name no profile has, as after a rename or a delete, or several have,
     * is told rather than saved.
     */
    fun save(edit: PolicyGroupEdit) = launchSave {
        if (edit.remarks.isBlank()) return@launchSave null
        val fallback = edit.fallbackTag.trim().takeIf { it.isNotEmpty() }
        val fallsBack = BalancerStrategyType.from(edit.type.toString()).supportsObservatory && edit.testOutbounds
        if (fallsBack && fallback != null && fallback !in BUILTIN_OUTBOUND_TAGS) {
            when (source.withProfileNames(CoreConfigContextBuilder::takesAsFallback) { find -> find(fallback) }) {
                ByName.None -> return@launchSave EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf(fallback))
                ByName.Several -> return@launchSave EditorOutcome.Refused(R.string.toast_profile_name_duplicate, listOf(fallback))
                is ByName.One -> Unit
            }
        }

        guid = source.saveProfile(guid, EConfigType.POLICYGROUP) { config ->
            config.remarks = edit.remarks.trim()
            config.policyGroupFilter = edit.filter.trim()
            config.policyGroupType = edit.type.toString()
            config.policyGroupSubscriptionId = edit.subscriptionId
            config.policyGroupTestOutbounds = edit.testOutbounds
            config.policyGroupFallbackTag = fallback
            if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
                config.subscriptionId = subscriptionId
            }
            config.description = "${edit.typeLabel} - ${edit.subscriptionLabel} - ${config.policyGroupFilter}"
        }
        EditorOutcome.Saved(guid)
    }
}
