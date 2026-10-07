package com.v2ray.ang.ui.routing

import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.server.ProfileNameSource
import com.v2ray.ang.ui.server.withStoredProfileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PattNG: where the routing rule editor finds the profile a rule sends to and stores, or deletes, the rule. */
interface RoutingEditSource : ProfileNameSource {
    /** Stores [rule] at [position], or first when [position] names no rule, see [SettingsManager.saveRoutingRuleset]. */
    suspend fun saveRule(position: Int, rule: RulesetItem)

    /** Deletes the rule at [position]. */
    suspend fun deleteRule(position: Int)
}

/**
 * PattNG: [RoutingEditSource] over [SettingsManager], which owns the routing rules: it only moves their reads and
 * writes off the main thread, and lets the editor's view model be tested without it.
 */
class RoutingEditRepository : RoutingEditSource {

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        withStoredProfileNames(takes, check)

    override suspend fun saveRule(position: Int, rule: RulesetItem) =
        withContext(Dispatchers.IO) { SettingsManager.saveRoutingRuleset(position, rule) }

    override suspend fun deleteRule(position: Int) =
        withContext(Dispatchers.IO) { SettingsManager.removeRoutingRuleset(position) }
}
