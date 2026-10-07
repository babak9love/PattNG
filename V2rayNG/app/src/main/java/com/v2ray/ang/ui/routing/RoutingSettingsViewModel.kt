package com.v2ray.ang.ui.routing

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoutingSettingsViewModel(
    application: Application,
    private val source: RoutingSettingsSource,
) : BaseViewModel(application) {
    private val rulesets: MutableList<RulesetItem> = mutableListOf()

    private val _rulesetsFlow = MutableStateFlow<List<RulesetItem>>(emptyList())
    val rulesetsFlow: StateFlow<List<RulesetItem>> = _rulesetsFlow.asStateFlow()

    /** PattNG: the reads and writes of the stored rules, off the main thread, one at a time, in the order asked for. */
    private val storage = Mutex()

    /** PattNG: the reading of the rules the screen asked for last, see [reload]. */
    private var reloadJob: Job? = null

    fun getAll(): List<RulesetItem> = rulesets.toList()

    /**
     * Reads the rules anew and shows them. PattNG: off the main thread, see [RoutingSettingsSource.loadRules]: one
     * without an id, or repeating another's, as the copy of a locked rule an import stored before it was left out, is
     * put right first, for the list, keyed by them, to show. A reload a later one replaces stops.
     */
    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val loaded = storage.withLock { source.loadRules() }
            rulesets.clear()
            rulesets.addAll(loaded)
            _rulesetsFlow.value = rulesets.toList()
        }
    }

    /**
     * Shows [item] in place of the rule at [position] and stores it, found again by its id. PattNG: not while the rules
     * are read anew, which would show what was read in its place; stored even when the screen closes right after.
     */
    fun update(position: Int, item: RulesetItem) {
        if (isReloading || position !in rulesets.indices) return
        rulesets[position] = item
        _rulesetsFlow.value = rulesets.toList()
        store { source.updateRule(item) }
    }

    /**
     * Shows the rule at [fromPosition] at [toPosition] and stores the list in that order. PattNG: not while the rules are
     * read anew; stored even when the screen closes right after.
     */
    fun move(fromPosition: Int, toPosition: Int) {
        if (isReloading || !rulesets.moveItem(fromPosition, toPosition)) return
        _rulesetsFlow.value = rulesets.toList()
        val order = rulesets.toList()
        store { source.storeRules(order) }
    }

    private val isReloading: Boolean
        get() = reloadJob?.isActive == true

    /** Runs [write] in its turn, to its end even when the screen is gone by then, which a change shown is owed. */
    private fun store(write: suspend () -> Unit) {
        viewModelScope.launch { withContext(NonCancellable) { storage.withLock { write() } } }
    }
}
