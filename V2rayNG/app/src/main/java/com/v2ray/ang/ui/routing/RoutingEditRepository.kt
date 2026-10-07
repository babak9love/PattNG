package com.v2ray.ang.ui.routing

import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.server.ProfileNameSource
import com.v2ray.ang.ui.server.withStoredProfileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PattNG: where the routing rule editor finds the profile a rule sends to and stores, or deletes, the rule. */
interface RoutingEditSource : ProfileNameSource {
    /**
     * Stores [rule] where the rule of its id is stored now, see [storedAt]; first when no rule has the id any more, or
     * when the rule is new, as [SettingsManager.saveRoutingRuleset] does. [position] is where the screen was opened on it.
     */
    suspend fun saveRule(position: Int, rule: RulesetItem)

    /** Deletes the rule of [id] where it is stored now, see [storedAt]; nothing when no rule has the id any more. */
    suspend fun deleteRule(position: Int, id: String)
}

/**
 * PattNG: where the rule of [id] stands among [rules] now, found again by its id, since the list may have changed while
 * the editor was open: -1 when no rule has it. A rule from before rules had ids goes by [position], where the screen was
 * opened on it, while the list reaches that far.
 */
internal fun storedAt(rules: List<RulesetItem>?, id: String, position: Int): Int {
    val list = rules.orEmpty()
    return if (id.isNotEmpty()) list.indexOfFirst { it.id == id } else position.takeIf { it in list.indices } ?: -1
}

/**
 * PattNG: the rule an editor opens on among [rules]: the one of [reopenedId], the id the editor kept, when it comes back
 * after its process was gone, else the one at [position]; null for none, as for a new rule or one gone.
 */
internal fun openedRule(rules: List<RulesetItem>?, position: Int, reopenedId: String?): RulesetItem? =
    if (reopenedId != null) rules?.firstOrNull { it.id == reopenedId } else rules?.getOrNull(position)

/**
 * PattNG: [RoutingEditSource] over [MmkvManager], which stores the routing rules: it moves their reads and writes off the
 * main thread, finding a rule again by its id, and lets the editor's view model be tested without it.
 */
class RoutingEditRepository : RoutingEditSource {

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        withStoredProfileNames(takes, check)

    // One read and one write each, as SettingsManager.saveRoutingRuleset and removeRoutingRuleset make them, so that the
    // rule found is the rule written however the list changes meanwhile.
    override suspend fun saveRule(position: Int, rule: RulesetItem) {
        withContext(Dispatchers.IO) {
            val rules = MmkvManager.decodeRoutingRulesets() ?: mutableListOf()
            when (val index = storedAt(rules, rule.id, position)) {
                -1 -> rules.add(0, rule)
                else -> rules[index] = rule
            }
            MmkvManager.encodeRoutingRulesets(rules)
        }
    }

    override suspend fun deleteRule(position: Int, id: String) {
        withContext(Dispatchers.IO) {
            val rules = MmkvManager.decodeRoutingRulesets() ?: return@withContext
            val index = storedAt(rules, id, position).takeIf { it >= 0 } ?: return@withContext
            rules.removeAt(index)
            MmkvManager.encodeRoutingRulesets(rules)
        }
    }
}
