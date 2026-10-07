package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ServerGroupViewModelTest {

    private val source = FakeProfileEditorSource()

    /** A type whose group tests its members, so that its fallback is used. */
    private val tested = BalancerStrategyType.RANDOM.policyGroupTypeValue.toInt()

    private val edit = PolicyGroupEdit(
        remarks = " group ",
        filter = " us ",
        type = tested,
        typeLabel = "Random",
        subscriptionId = "picked",
        subscriptionLabel = "Picked",
        testOutbounds = true,
        fallbackTag = " exit ",
    )

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

    private fun viewModel(guid: String = "", subscriptionId: String? = null) =
        ServerGroupViewModel(mock<Application>(), source, guid, subscriptionId)

    @Test
    fun blankRemarksSaveNothingAndTellNothing() {
        val viewModel = viewModel()

        viewModel.save(edit.copy(remarks = " "))

        assertNull(viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aFallbackNoProfileHasOrSeveralHaveIsToldByItsName() {
        source.names.add("twice")
        source.names.add("twice")
        val viewModel = viewModel()

        viewModel.save(edit.copy(fallbackTag = " gone "))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf("gone")), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "twice"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_duplicate, listOf("twice")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun theFallbackIsFoundAmongTheProfilesAGroupCanFallBackTo() {
        // A group cannot fall back to a group, so it does not find one by its name.
        source.names.add("other group", EConfigType.POLICYGROUP)
        val viewModel = viewModel()

        viewModel.save(edit.copy(fallbackTag = "other group"))

        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf("other group")), viewModel.outcome.value)
    }

    @Test
    fun aFallbackIsLookedUpOnlyWhereItIsUsed() {
        val viewModel = viewModel()

        // Not when the group does not test its members, nor when its type cannot, nor for a built-in outbound.
        viewModel.save(edit.copy(fallbackTag = "gone", testOutbounds = false))
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "gone", type = BalancerStrategyType.LEAST_LOAD.policyGroupTypeValue.toInt()))
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "direct"))

        assertEquals(0, source.names.lookups)
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertEquals(3, source.saves.size)
    }

    @Test
    fun aNewGroupIsStoredOnceAndALaterSaveWritesOverIt() {
        val viewModel = viewModel(subscriptionId = "sub")

        viewModel.save(edit)
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        val stored = source.stored.getValue("guid-1")
        assertEquals(EConfigType.POLICYGROUP, stored.configType)
        assertEquals("group", stored.remarks)
        assertEquals("us", stored.policyGroupFilter)
        assertEquals(tested.toString(), stored.policyGroupType)
        assertEquals("picked", stored.policyGroupSubscriptionId)
        assertEquals(true, stored.policyGroupTestOutbounds)
        assertEquals("exit", stored.policyGroupFallbackTag)
        assertEquals("sub", stored.subscriptionId)
        assertEquals("Random - Picked - us", stored.description)

        // As when the screen, recreated before it closed, is saved again: the group it stored is written over.
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(remarks = "group 2", fallbackTag = " "))

        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertEquals(listOf("guid-1", "guid-1"), source.saves)
        assertEquals("group 2", source.stored.getValue("guid-1").remarks)
        assertNull(source.stored.getValue("guid-1").policyGroupFallbackTag)
    }

    @Test
    fun aStoredGroupIsWrittenOverAndKeepsItsSubscription() {
        source.stored["group-guid"] = ProfileItem.create(EConfigType.POLICYGROUP).apply { subscriptionId = "own" }
        val viewModel = viewModel(guid = "group-guid", subscriptionId = "sub")

        viewModel.save(edit)

        assertEquals(EditorOutcome.Saved("group-guid"), viewModel.outcome.value)
        assertEquals(listOf("group-guid"), source.saves)
        assertEquals("own", source.stored.getValue("group-guid").subscriptionId)
    }

    @Test
    fun theScreenMayNotCloseWhileTheFallbackIsLookedUp() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save(edit)
        assertFalse(viewModel.leaveScreen())
        gate.complete(Unit)

        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertTrue(viewModel.leaveScreen())
    }

    @Test
    fun theSubscriptionsAreOfferedAllFirstUnderLabelsNoTwoOfWhichAreAlike() {
        val offered = policyGroupSubscriptions(
            all = "All",
            subscriptions = listOf("a1" to "Iran", "b2" to "Iran", "c3" to "All", "d4" to "Work"),
            numbered = { name, number -> "$name ($number)" },
        )

        assertEquals(
            listOf(
                PolicyGroupSubscription("", "All"),
                PolicyGroupSubscription("a1", "Iran"),
                PolicyGroupSubscription("b2", "Iran (2)"),
                PolicyGroupSubscription("c3", "All (2)"),
                PolicyGroupSubscription("d4", "Work"),
            ),
            offered,
        )
        // So each label the list hands back leads to one subscription.
        assertEquals(offered.size, offered.map { it.label }.toSet().size)
        assertEquals(listOf(PolicyGroupSubscription("", "All")), policyGroupSubscriptions("All", emptyList()) { name, number -> "$name ($number)" })
    }

    @Test
    fun aSubscriptionIsPickedByItsKeyAndAGoneOneGivesAll() {
        val offered = policyGroupSubscriptions("All", listOf("a1" to "Iran", "b2" to "Iran")) { name, number -> "$name ($number)" }

        assertEquals(PolicyGroupSubscription("b2", "Iran (2)"), offered.pick("b2"))
        assertEquals(PolicyGroupSubscription("a1", "Iran"), offered.pick("a1"))
        assertEquals(PolicyGroupSubscription("", "All"), offered.pick(""))
        assertEquals(PolicyGroupSubscription("", "All"), offered.pick("gone"))
        assertEquals(PolicyGroupSubscription("", "All"), offered.pick(null))
    }

    @Test
    fun aDeleteDeletesTheGroupUnlessTheAppRunsOnIt() {
        source.stored["group-guid"] = ProfileItem.create(EConfigType.POLICYGROUP)
        source.selected = "group-guid"
        val viewModel = viewModel(guid = "group-guid")

        viewModel.delete()
        assertEquals(EditorOutcome.Refused(R.string.toast_action_not_allowed), viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())

        viewModel.onOutcomeHandled()
        source.selected = null
        viewModel.delete()
        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf("group-guid"), source.deletes)
    }

    @Test
    fun aWriteTheStorageRefusesIsTold() {
        source.refuseWrites = true
        val viewModel = viewModel()

        viewModel.save(edit)

        assertEquals(EditorOutcome.Refused(R.string.toast_failure), viewModel.outcome.value)
        assertTrue(source.stored.isEmpty())
    }
}
