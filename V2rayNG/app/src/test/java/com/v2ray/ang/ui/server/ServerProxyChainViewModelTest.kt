package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ServerProxyChainViewModelTest {

    private val source = FakeProfileEditorSource()

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.names.add("entry")
        source.names.add("exit")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(guid: String = "", subscriptionId: String? = null) =
        ServerProxyChainViewModel(mock<Application>(), source, guid, subscriptionId)

    private fun refused(message: Int, vararg args: String) = EditorOutcome.Refused(message, args.toList())

    @Test
    fun blankRemarksSaveNothingAndTellNothing() {
        val viewModel = viewModel()

        viewModel.save("  ", listOf("entry", "exit"))

        assertNull(viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aRowLeftUnchosenAndAChainOfOneAreTold() {
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", " "))
        assertEquals(refused(R.string.server_proxy_chain_members_unselected), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("entry"))
        assertEquals(refused(R.string.server_proxy_chain_members_insufficient), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
        assertEquals(0, source.names.lookups)
    }

    @Test
    fun aMemberNoProfileHasSeveralHaveOrWithoutAServerIsToldByItsName() {
        source.names.add("twice")
        source.names.add("twice")
        source.names.add("bare", server = null)
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "gone"))
        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.NOT_FOUND.message, "gone"), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf(" twice ", "exit"))
        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.SEVERAL.message, "twice"), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("entry", "bare"))
        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.NO_SERVER.message, "bare"), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun membersAreFoundAmongTheProfilesAChainCanGoThrough() {
        // A policy group cannot be a hop, so a chain does not find it by its name.
        source.names.add("group", EConfigType.POLICYGROUP)
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "group"))

        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.NOT_FOUND.message, "group"), viewModel.outcome.value)
    }

    @Test
    fun aSecondAetherMemberIsTold() {
        source.names.add("warp", EConfigType.AETHER, server = null)
        source.names.add("warp 2", EConfigType.AETHER, server = null)
        val viewModel = viewModel()

        viewModel.save("chain", listOf("warp", "entry", "warp 2"))

        assertEquals(refused(R.string.aether_chain_one_profile), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aNewChainIsStoredOnceAndALaterSaveWritesOverIt() {
        val viewModel = viewModel(subscriptionId = "sub")

        viewModel.save(" chain ", listOf(" entry", "exit "))
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        val stored = source.stored.getValue("guid-1")
        assertEquals(EConfigType.PROXYCHAIN, stored.configType)
        assertEquals("chain", stored.remarks)
        assertEquals(listOf("entry", "exit"), ProfileItem.proxyChainMembersOf(stored.proxyChainProfiles))
        assertEquals("entry -> exit", stored.description)
        assertEquals("sub", stored.subscriptionId)

        // As when the screen, recreated before it closed, is saved again: the chain it stored is written over.
        viewModel.onOutcomeHandled()
        viewModel.save("chain 2", listOf("exit", "entry"))

        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertEquals(listOf("", "guid-1"), source.saves)
        assertEquals(setOf("guid-1"), source.stored.keys)
        assertEquals("chain 2", source.stored.getValue("guid-1").remarks)
    }

    @Test
    fun aStoredChainIsWrittenOverAndKeepsItsSubscription() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN).apply { subscriptionId = "own" }
        val viewModel = viewModel(guid = "chain-guid", subscriptionId = "sub")

        viewModel.save("chain", listOf("entry", "exit"))

        assertEquals(EditorOutcome.Saved("chain-guid"), viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.saves)
        assertEquals("own", source.stored.getValue("chain-guid").subscriptionId)
    }

    @Test
    fun aMemberWhoseNameHasACommaIsStoredSoThatItReadsBack() {
        source.names.add("a, b")
        val viewModel = viewModel()

        viewModel.save("chain", listOf("a, b", "exit"))

        assertEquals(listOf("a, b", "exit"), ProfileItem.proxyChainMembersOf(source.stored.getValue("guid-1").proxyChainProfiles))
    }

    @Test
    fun aSecondTapWhileTheSaveRunsSavesNothingMore() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "exit"))
        viewModel.save("chain", listOf("entry", "exit"))
        gate.complete(Unit)

        assertEquals(1, source.names.lookups)
        assertEquals(listOf(""), source.saves)
    }

    @Test
    fun leavingTheScreenWhileTheMembersAreLookedUpStoresNothing() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "exit"))
        viewModel.onScreenLeft()
        gate.complete(Unit)

        assertTrue(source.saves.isEmpty())
        assertNull(viewModel.outcome.value)
    }

    @Test
    fun aDeleteDeletesTheChainUnlessTheAppRunsOnIt() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN)
        source.selected = "chain-guid"
        val viewModel = viewModel(guid = "chain-guid")

        viewModel.delete()
        assertEquals(refused(R.string.toast_action_not_allowed), viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())

        // Told, the screen stays open, and deletes once the app runs on another profile.
        viewModel.onOutcomeHandled()
        source.selected = "other"
        viewModel.delete()
        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.deletes)
    }

    @Test
    fun aDeleteConfirmedWhileTheMembersAreLookedUpStopsTheSaveAndDeletes() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN)
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel(guid = "chain-guid")

        viewModel.save("chain", listOf("entry", "exit"))
        viewModel.delete()
        gate.complete(Unit)

        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.deletes)
        assertTrue(source.saves.isEmpty())
        assertTrue(source.stored.isEmpty())
    }

    @Test
    fun aNewChainHasNoneToDelete() {
        val viewModel = viewModel()

        viewModel.delete()

        assertNull(viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())
    }

    @Test
    fun aDeleteRefusedWhileTheMembersAreLookedUpLeavesTheSaveToGoOn() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN)
        source.selected = "chain-guid"
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel(guid = "chain-guid")

        viewModel.save("chain", listOf("entry", "exit"))
        viewModel.delete()
        assertEquals(refused(R.string.toast_action_not_allowed), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        gate.complete(Unit)
        assertEquals(EditorOutcome.Saved("chain-guid"), viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.saves)
        assertTrue(source.deletes.isEmpty())
    }
}
