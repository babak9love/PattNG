package com.v2ray.ang.ui.base

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class EditorViewModelTest {

    private class Editor : EditorViewModel(mock<Application>()) {
        fun save(work: suspend () -> EditorOutcome?) = launchSave(work)
        fun delete(work: suspend () -> EditorOutcome) = launchDelete(work)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsIdleWithNoOutcome() {
        val editor = Editor()

        assertNull(editor.outcome.value)
        assertFalse(editor.isBusy)
    }

    @Test
    fun aSaveEndsInWhatItGivesAndASaveThatGivesNothingInNoOutcome() {
        val editor = Editor()

        editor.save { null }
        assertNull(editor.outcome.value)

        editor.save { EditorOutcome.Saved("guid") }
        assertEquals(EditorOutcome.Saved("guid"), editor.outcome.value)
    }

    @Test
    fun theScreenActsOnAnOutcomeOnce() {
        val editor = Editor()
        editor.save { EditorOutcome.Refused(1, listOf("name")) }
        assertEquals(EditorOutcome.Refused(1, listOf("name")), editor.outcome.value)

        editor.onOutcomeHandled()

        assertNull(editor.outcome.value)
    }

    @Test
    fun oneSaveRunsAtATime() {
        val editor = Editor()
        val gate = CompletableDeferred<Unit>()
        var second = false
        editor.save { gate.await(); EditorOutcome.Saved("first") }

        assertTrue(editor.isBusy)
        editor.save { second = true; EditorOutcome.Saved("second") }
        assertFalse(second)

        gate.complete(Unit)
        assertFalse(editor.isBusy)
        assertEquals(EditorOutcome.Saved("first"), editor.outcome.value)

        // Once it is done, the next one runs.
        editor.save { second = true; EditorOutcome.Saved("second") }
        assertTrue(second)
        assertEquals(EditorOutcome.Saved("second"), editor.outcome.value)
    }

    @Test
    fun aDeleteStopsASaveThatHasNotWrittenAndRunsInItsPlace() {
        val editor = Editor()
        val lookup = CompletableDeferred<Unit>()
        var written = false
        var deleted = false
        editor.save { lookup.await(); written = true; EditorOutcome.Saved("guid") }

        // Confirmed while the save looks names up: the delete is not dropped, and the save does not write after it.
        editor.delete { deleted = true; EditorOutcome.Deleted }
        lookup.complete(Unit)

        assertTrue(deleted)
        assertFalse(written)
        assertEquals(EditorOutcome.Deleted, editor.outcome.value)
    }

    @Test
    fun aDeleteWaitsForTheWriteOfASaveAndRunsAfterIt() {
        val editor = Editor()
        val write = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        editor.save {
            // A write under way ends, as one on Dispatchers.IO does when its coroutine is cancelled.
            withContext(NonCancellable) { write.await(); order += "written" }
            EditorOutcome.Saved("guid")
        }

        editor.delete { order += "deleted"; EditorOutcome.Deleted }
        assertTrue(order.isEmpty())
        write.complete(Unit)

        assertEquals(listOf("written", "deleted"), order)
        assertEquals(EditorOutcome.Deleted, editor.outcome.value)
    }

    @Test
    fun aRefusedDeleteLeavesTheScreenOpenForSavesAndDeletes() {
        val editor = Editor()
        editor.delete { EditorOutcome.Refused(1) }
        assertEquals(EditorOutcome.Refused(1), editor.outcome.value)

        editor.onOutcomeHandled()
        editor.save { EditorOutcome.Saved("guid") }
        assertEquals(EditorOutcome.Saved("guid"), editor.outcome.value)
        editor.onOutcomeHandled()
        editor.delete { EditorOutcome.Deleted }
        assertEquals(EditorOutcome.Deleted, editor.outcome.value)
    }

    @Test
    fun nothingStartsOnceADeleteHasAndItEndsInDeleted() {
        val editor = Editor()
        val gate = CompletableDeferred<Unit>()
        var deletes = 0
        var saved = false
        editor.delete { deletes++; gate.await(); EditorOutcome.Deleted }

        editor.save { saved = true; EditorOutcome.Saved("guid") }
        editor.delete { deletes++; EditorOutcome.Deleted }
        gate.complete(Unit)
        assertEquals(EditorOutcome.Deleted, editor.outcome.value)

        // Not after it either: the save would write back what it deleted.
        editor.onOutcomeHandled()
        editor.save { saved = true; EditorOutcome.Saved("guid") }
        editor.delete { deletes++; EditorOutcome.Deleted }

        assertFalse(saved)
        assertEquals(1, deletes)
        assertNull(editor.outcome.value)
    }

    @Test
    fun leavingTheScreenStopsASaveThatHasNotWrittenAndStartsNothingMore() {
        val editor = Editor()
        val lookup = CompletableDeferred<Unit>()
        var written = false
        editor.save {
            lookup.await()
            written = true
            EditorOutcome.Saved("guid")
        }

        editor.onScreenLeft()
        lookup.complete(Unit)

        assertFalse(written)
        assertFalse(editor.isBusy)
        assertNull(editor.outcome.value)

        var started = false
        editor.save { started = true; EditorOutcome.Saved("guid") }
        editor.delete { started = true; EditorOutcome.Deleted }
        assertFalse(started)
        assertNull(editor.outcome.value)
    }

    @Test
    fun leavingTheScreenStopsADeleteThatHasNotWritten() {
        val editor = Editor()
        val gate = CompletableDeferred<Unit>()
        var deleted = false
        editor.delete {
            gate.await()
            deleted = true
            EditorOutcome.Deleted
        }

        editor.onScreenLeft()
        gate.complete(Unit)

        assertFalse(deleted)
        assertNull(editor.outcome.value)
    }
}
