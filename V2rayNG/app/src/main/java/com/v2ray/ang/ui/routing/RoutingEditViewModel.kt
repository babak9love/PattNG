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
 * PattNG: the save and the delete of the routing rule editor, see [EditorViewModel]. The rule is stored at [position]:
 * the one the screen was opened on, or, for a new rule (a negative one), the first, where its first save puts it, and
 * where a later save writes over it.
 */
class RoutingEditViewModel(
    application: Application,
    private val source: RoutingEditSource,
    private var position: Int,
) : EditorViewModel(application) {

    /** The id of a new rule, given once, so that every save of it keeps it; null for a rule that was stored already. */
    private val newRuleId: String? = if (position < 0) UUID.randomUUID().toString() else null

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

        if (newRuleId != null && rule.id.isEmpty()) {
            rule.id = newRuleId
        }
        source.saveRule(position, rule)
        if (position < 0) {
            position = 0
        }
        EditorOutcome.Saved(rule.id)
    }

    /** Deletes the rule, unless a save runs, which would write it back; a new rule, never stored, has none to delete. */
    fun delete() {
        val stored = position.takeIf { it >= 0 } ?: return
        launchDelete { source.deleteRule(stored) }
    }
}
