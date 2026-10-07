package com.v2ray.ang.ui.routing

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PattNG: where the routing settings screen reads its rules and stores what it changes in their list, the order and
 * whether each is on, see [RoutingSettingsViewModel]; the editor of one rule has its own, see [RoutingEditSource].
 */
interface RoutingSettingsSource {
    /**
     * The stored rules with an id of their own each, see [SettingsManager.rulesetsWithOwnIds]: put right, and stored so,
     * when they were not.
     */
    suspend fun loadRules(): List<RulesetItem>

    /** Stores [rule] in place of the stored rule with its id; nothing when no rule has it any more. */
    suspend fun updateRule(rule: RulesetItem)

    /** Stores [rules], the whole list, in the order the screen shows it. */
    suspend fun storeRules(rules: List<RulesetItem>)
}

/**
 * PattNG: [RoutingSettingsSource] over [MmkvManager], which stores the routing rules: it moves their reads and writes
 * off the main thread, logs a write the storage refused, and lets the screen's view model be tested without it.
 */
class RoutingSettingsRepository : RoutingSettingsSource {

    override suspend fun loadRules(): List<RulesetItem> = withContext(Dispatchers.IO) {
        val stored = MmkvManager.decodeRoutingRulesets().orEmpty()
        val ownIds = SettingsManager.rulesetsWithOwnIds(stored) ?: return@withContext stored
        // Shown so even when the storage refuses them, which a list keyed by the ids could not be otherwise.
        written(MmkvManager.encodeRoutingRulesets(ownIds), "the rules with ids of their own")
        ownIds
    }

    override suspend fun updateRule(rule: RulesetItem) {
        withContext(Dispatchers.IO) {
            val rules = MmkvManager.decodeRoutingRulesets() ?: return@withContext
            val index = rules.indexOfFirst { it.id == rule.id }.takeIf { it >= 0 } ?: return@withContext
            rules[index] = rule
            written(MmkvManager.encodeRoutingRulesets(rules), "rule ${rule.id}")
        }
    }

    override suspend fun storeRules(rules: List<RulesetItem>) {
        withContext(Dispatchers.IO) { written(MmkvManager.encodeRoutingRulesets(rules.toMutableList()), "the order of the rules") }
    }

    private fun written(taken: Boolean, what: String) {
        if (!taken) LogUtil.e(AppConfig.TAG, "Routing settings: the storage refused $what")
    }
}
