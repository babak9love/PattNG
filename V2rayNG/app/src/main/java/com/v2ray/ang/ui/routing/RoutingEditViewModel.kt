package com.v2ray.ang.ui.routing

import android.app.Application
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.base.EditorViewModel
import java.util.UUID

/**
 * PattNG: the save and the delete of the routing rule editor, see [EditorViewModel]. The screen was opened on the rule
 * at [position], whose id is [storedId], or on a new rule, at a negative position. The rule is written where it is
 * found again by its id, see [RoutingEditSource.saveRule]: the list may have changed while the editor was open.
 */
class RoutingEditViewModel(
    application: Application,
    private val source: RoutingEditSource,
    private val position: Int,
    storedId: String,
) : EditorViewModel(application) {

    /**
     * The id of the rule: the one it is stored with, or, for a new rule, one given once, which every save of it keeps.
     * Blank for a stored rule from before rules had ids, which goes by its position.
     */
    private val id: String = storedId.ifEmpty { if (position < 0) UUID.randomUUID().toString() else "" }

    /**
     * Saves [rule]. A rule that sends to a profile names it, and the name has to find that one profile, as at the start:
     * a name no profile has, as after a rename or a delete, or several have, is told rather than saved. The start looks
     * at enabled rules alone, and so does this.
     */
    fun save(rule: RulesetItem) = launchSave {
        if (rule.remarks.isNullOrEmpty()) return@launchSave null
        val tag = rule.outboundTag
        if (rule.enabled && tag !in BUILTIN_OUTBOUND_TAGS) {
            when (source.withProfileNames(CoreConfigContextBuilder::takesAsRoutingTarget) { find -> find(tag) }) {
                ByName.None -> return@launchSave EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf(tag.trim()))
                ByName.Several -> return@launchSave EditorOutcome.Refused(R.string.toast_profile_name_duplicate, listOf(tag.trim()))
                is ByName.One -> Unit
            }
        }

        if (rule.id.isEmpty()) {
            rule.id = id
        }
        source.saveRule(position, rule)
        EditorOutcome.Saved(rule.id)
    }

    /** Deletes the rule, found again by its id, see [EditorViewModel.launchDelete]; a new rule, never stored, has none to delete. */
    fun delete() {
        if (position < 0) return
        launchDelete { source.deleteRule(position, id) }
    }
}
