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
     * when the rule is new, see [SettingsManager.saveRoutingRuleset]. [position] is where the screen was opened on it.
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
 * PattNG: [RoutingEditSource] over [SettingsManager] and [MmkvManager], which own the routing rules: it only moves their
 * reads and writes off the main thread, finding a rule again by its id, and lets the editor's view model be tested
 * without them.
 */
class RoutingEditRepository : RoutingEditSource {

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        withStoredProfileNames(takes, check)

    override suspend fun saveRule(position: Int, rule: RulesetItem) =
        withContext(Dispatchers.IO) { SettingsManager.saveRoutingRuleset(storedAt(MmkvManager.decodeRoutingRulesets(), rule.id, position), rule) }

    override suspend fun deleteRule(position: Int, id: String) {
        withContext(Dispatchers.IO) {
            storedAt(MmkvManager.decodeRoutingRulesets(), id, position).takeIf { it >= 0 }?.let { SettingsManager.removeRoutingRuleset(it) }
        }
    }
}
