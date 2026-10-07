package com.v2ray.ang.ui.routing

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.server.FakeProfileNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class RoutingEditViewModelTest {

    private class FakeSource : RoutingEditSource {
        val names = FakeProfileNames()

        /** The position each save was asked to store at, with a copy of the rule as it was then. */
        val saves = mutableListOf<Pair<Int, RulesetItem>>()
        /** The position and the id each delete was asked for. */
        val deletes = mutableListOf<Pair<Int, String>>()

        override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
            names.withProfileNames(takes, check)

        override suspend fun saveRule(position: Int, rule: RulesetItem) {
            saves += position to rule.copy()
        }

        override suspend fun deleteRule(position: Int, id: String) {
            deletes += position to id
        }
    }

    private val source = FakeSource()

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.names.add("exit")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(position: Int = -1, storedId: String = "") = RoutingEditViewModel(mock<Application>(), source, position, storedId)

    private fun rule(tag: String = "exit", enabled: Boolean = true, id: String = "") =
        RulesetItem(id = id, remarks = "rule", outboundTag = tag, enabled = enabled)

    @Test
    fun aRuleWithoutRemarksSavesNothingAndTellsNothing() {
        val viewModel = viewModel()

        viewModel.save(rule().apply { remarks = "" })

        assertNull(viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun anEnabledRuleSendingToAProfileNoneOrSeveralHaveIsToldByItsName() {
        source.names.add("twice")
        source.names.add("twice")
        val viewModel = viewModel()

        viewModel.save(rule(tag = " gone "))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf("gone")), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save(rule(tag = "twice"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_duplicate, listOf("twice")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun theProfileIsFoundAmongThoseARuleCanSendTo() {
        // A custom profile is not a routing target, so a rule does not find it by its name.
        source.names.add("custom", EConfigType.CUSTOM)
        val viewModel = viewModel()

        viewModel.save(rule(tag = "custom"))

        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf("custom")), viewModel.outcome.value)
    }

    @Test
    fun aDisabledRuleAndARuleToABuiltInOutboundAreSavedWithoutALookup() {
        val viewModel = viewModel(position = 2)

        viewModel.save(rule(tag = "gone", enabled = false))
        viewModel.onOutcomeHandled()
        viewModel.save(rule(tag = AppConfig.TAG_DIRECT))

        assertEquals(0, source.names.lookups)
        assertEquals(listOf(2, 2), source.saves.map { it.first })
    }

    @Test
    fun aNewRuleGetsOneIdThatEverySaveOfItKeeps() {
        val viewModel = viewModel()

        viewModel.save(rule())
        val (position, stored) = source.saves.single()
        assertEquals(-1, position)
        assertTrue(stored.id.isNotEmpty())
        assertEquals(EditorOutcome.Saved(stored.id), viewModel.outcome.value)

        // As when the screen, recreated before it closed, is saved again: the rule keeps its id, by which the rule it stored
        // first is found again and written over.
        viewModel.onOutcomeHandled()
        viewModel.save(rule())

        assertEquals(-1 to stored.id, source.saves[1].first to source.saves[1].second.id)
        assertEquals(EditorOutcome.Saved(stored.id), viewModel.outcome.value)
    }

    @Test
    fun aStoredRuleIsSavedWithTheIdItIsStoredWith() {
        val viewModel = viewModel(position = 3, storedId = "rule-id")

        viewModel.save(rule(id = "rule-id"))
        viewModel.onOutcomeHandled()
        viewModel.save(rule())

        assertEquals(listOf(3 to "rule-id", 3 to "rule-id"), source.saves.map { it.first to it.second.id })

        // One from before rules had ids keeps none, and goes by its position.
        val legacy = viewModel(position = 4)
        legacy.save(rule())
        assertEquals(4 to "", source.saves[2].first to source.saves[2].second.id)
    }

    @Test
    fun aRuleIsFoundAgainByItsIdWhereverTheListHasMovedIt() {
        val rules = listOf(RulesetItem(id = "a"), RulesetItem(id = "b"), RulesetItem(id = "c"))

        assertEquals(1, storedAt(rules, "b", 1))
        // Moved, or with a rule deleted before it, while the editor was open.
        assertEquals(0, storedAt(listOf(rules[1], rules[0], rules[2]), "b", 1))
        assertEquals(0, storedAt(rules.drop(1), "b", 1))
        // Gone, or new: no rule has the id.
        assertEquals(-1, storedAt(rules, "gone", 1))
        assertEquals(-1, storedAt(null, "b", 1))
        // A rule without an id goes by its position, while the list reaches that far.
        assertEquals(2, storedAt(rules, "", 2))
        assertEquals(-1, storedAt(rules, "", 3))
        assertEquals(-1, storedAt(rules, "", -1))
    }

    @Test
    fun aDeleteDeletesTheRuleAtItsPositionAndANewRuleHasNoneToDelete() {
        val stored = viewModel(position = 3, storedId = "rule-id")
        stored.delete()
        assertEquals(listOf(3 to "rule-id"), source.deletes)
        assertEquals(EditorOutcome.Deleted, stored.outcome.value)

        val new = viewModel()
        new.delete()
        assertEquals(listOf(3 to "rule-id"), source.deletes)
        assertNull(new.outcome.value)
    }

    @Test
    fun leavingTheScreenWhileTheProfileIsLookedUpStoresNothing() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save(rule())
        viewModel.onScreenLeft()
        gate.complete(Unit)

        assertTrue(source.saves.isEmpty())
        assertNull(viewModel.outcome.value)
    }
}
