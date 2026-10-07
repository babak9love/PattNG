package com.v2ray.ang.ui.routing

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.dto.entities.RulesetItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class RoutingSettingsViewModelTest {

    /** The stored rules, in memory, with what the screen asked to store of them. */
    private class FakeSource : RoutingSettingsSource {
        var rules: List<RulesetItem> = emptyList()
        var loads = 0

        /** When set, a read waits for it, as one off the main thread takes its time. */
        var loadGate: CompletableDeferred<Unit>? = null

        /** When set, a write waits for it before it is done. */
        var writeGate: CompletableDeferred<Unit>? = null

        /** Each write as it started, and as it was done. */
        val started = mutableListOf<String>()
        val done = mutableListOf<String>()
        val updated = mutableListOf<RulesetItem>()
        val stored = mutableListOf<List<RulesetItem>>()

        override suspend fun loadRules(): List<RulesetItem> {
            loads++
            loadGate?.await()
            return rules
        }

        override suspend fun updateRule(rule: RulesetItem) {
            started += "update ${rule.id}"
            writeGate?.await()
            updated += rule
            done += "update ${rule.id}"
        }

        override suspend fun storeRules(rules: List<RulesetItem>) {
            started += "store"
            writeGate?.await()
            stored += rules
            done += "store"
        }
    }

    private val source = FakeSource()
    private val a = RulesetItem(id = "a", remarks = "a", outboundTag = "proxy")
    private val b = RulesetItem(id = "b", remarks = "b", outboundTag = "direct")

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.rules = listOf(a, b)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RoutingSettingsViewModel(mock<Application>(), source)

    @Test
    fun startsEmptyAndShowsWhatAReloadReads() {
        val viewModel = viewModel()
        assertTrue(viewModel.rulesetsFlow.value.isEmpty())

        viewModel.reload()

        assertEquals(listOf(a, b), viewModel.rulesetsFlow.value)
        assertEquals(1, source.loads)
    }

    @Test
    fun aRuleTurnedOffIsShownSoAtOnceAndStoredByItself() {
        val viewModel = viewModel()
        viewModel.reload()
        val off = a.copy(enabled = false)

        viewModel.update(0, off)

        assertEquals(listOf(off, b), viewModel.rulesetsFlow.value)
        assertEquals(listOf(off), source.updated)
        // A position the list does not reach changes nothing.
        viewModel.update(2, off)
        assertEquals(listOf(off), source.updated)
    }

    @Test
    fun aRuleMovedIsShownSoAtOnceAndTheListStoredInThatOrder() {
        val viewModel = viewModel()
        viewModel.reload()

        viewModel.move(0, 1)

        assertEquals(listOf(b, a), viewModel.rulesetsFlow.value)
        assertEquals(listOf(listOf(b, a)), source.stored)
        // A move to where the rule is, or out of the list, changes nothing.
        viewModel.move(1, 1)
        viewModel.move(0, 5)
        assertEquals(1, source.stored.size)
    }

    @Test
    fun nothingIsChangedWhileTheRulesAreReadAnew() {
        val viewModel = viewModel()
        viewModel.reload()
        val gate = CompletableDeferred<Unit>()
        source.loadGate = gate
        source.rules = listOf(a, b, RulesetItem(id = "c", remarks = "c"))

        viewModel.reload()
        viewModel.update(0, a.copy(enabled = false))
        viewModel.move(0, 1)
        gate.complete(Unit)

        // What was read is shown, and nothing stored over it from the list shown before.
        assertEquals(listOf("a", "b", "c"), viewModel.rulesetsFlow.value.map { it.id })
        assertTrue(source.started.isEmpty())
    }

    @Test
    fun writesAreDoneOneAtATimeInTheOrderAskedFor() {
        val viewModel = viewModel()
        viewModel.reload()
        val gate = CompletableDeferred<Unit>()
        source.writeGate = gate

        viewModel.update(0, a.copy(enabled = false))
        viewModel.move(0, 1)
        assertEquals(listOf("update a"), source.started)

        gate.complete(Unit)
        assertEquals(listOf("update a", "store"), source.done)
    }

    @Test
    fun aReloadALaterOneReplacesShowsNothing() {
        val viewModel = viewModel()
        val gate = CompletableDeferred<Unit>()
        source.loadGate = gate

        viewModel.reload()
        source.loadGate = null
        source.rules = listOf(b)
        viewModel.reload()
        gate.complete(Unit)

        assertEquals(listOf(b), viewModel.rulesetsFlow.value)
    }

    @Test
    fun aChangeShownIsStoredThoughTheScreenClosesBeforeItsTurn() {
        val viewModel = viewModel()
        viewModel.reload()
        val gate = CompletableDeferred<Unit>()
        source.writeGate = gate

        viewModel.update(0, a.copy(enabled = false))
        viewModel.move(0, 1)
        // The screen closes, which ends the view model's scope, while one write waits and the other for its turn.
        viewModel.viewModelScope.cancel()
        gate.complete(Unit)

        assertEquals(listOf("update a", "store"), source.done)
    }
}
