package com.v2ray.ang.ui.base

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** PattNG: what the save or the delete of an editor screen ends with, for the screen to act on once, see [EditorViewModel.outcome]. */
sealed interface EditorOutcome {
    /** Written, as [key]: the guid of the profile, the key of the subscription, or the id of the routing rule. */
    data class Saved(val key: String) : EditorOutcome

    /** Not written, for [message], whose arguments are [args]: the screen tells it and stays open. */
    data class Refused(@StringRes val message: Int, val args: List<String> = emptyList()) : EditorOutcome

    /** Deleted: the screen closes. */
    data object Deleted : EditorOutcome
}

/**
 * PattNG: the save and the delete of an editor screen that reads and writes off the main thread. They run here rather
 * than in the activity's lifecycle scope, which ends when the activity is recreated, as on a rotation: a save cut off
 * there was lost, or written with nothing told, and the screen, still open on what it was opened with, stored a second
 * copy at the next tap. Here one runs at a time, a delete stops a save first, nothing starts once a delete has, and the
 * [outcome] reaches whichever activity shows the screen when it comes. A view model of a screen keeps what its saves
 * stored as, for the next one to write over. The screen waits for the save or the delete that runs before it closes,
 * see [leaveScreen].
 */
abstract class EditorViewModel(application: Application) : BaseViewModel(application) {

    private val _outcome = MutableStateFlow<EditorOutcome?>(null)

    /** The outcome the screen has not acted on yet, see [onOutcomeHandled]; null while there is none. */
    val outcome: StateFlow<EditorOutcome?> = _outcome.asStateFlow()

    private var saveJob: Job? = null
    private var deleteJob: Job? = null
    private var deleting = false
    private var left = false

    /** Whether a save or a delete runs. */
    val isBusy: Boolean
        get() = saveJob?.isActive == true || deleteJob?.isActive == true

    private val _busy = MutableStateFlow(false)

    /** [isBusy], for the screen to observe: Back waits for what runs, see [leaveScreen]. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Runs [save], unless a save or a delete runs, a delete has started, or the screen is left. What it ends with goes
     * to [outcome]; null, as when the screen shows a field's error itself, goes nowhere.
     */
    protected fun launchSave(save: suspend () -> EditorOutcome?) {
        if (isBusy || deleting || left) return
        saveJob = viewModelScope.launch { save()?.let { _outcome.value = it } }
        watch(saveJob)
    }

    /**
     * Runs [delete], unless a delete has started or the screen is left; then [outcome] is [EditorOutcome.Deleted]. A
     * delete [refuse] gives a refusal for is not run: the refusal goes to [outcome], a save that runs goes on, and the
     * screen stays open for saves and deletes. Otherwise a save that runs is stopped first: one that has not written
     * does not, and one that writes ends its write before [delete] starts, so that it cannot write back what is deleted.
     * A delete the storage refuses gives the refusal it ends with, which goes to [outcome] in place of
     * [EditorOutcome.Deleted]; the screen stays open as well.
     */
    protected fun launchDelete(refuse: (suspend () -> EditorOutcome.Refused?)? = null, delete: suspend () -> EditorOutcome.Refused?) {
        if (deleting || left) return
        deleting = true
        deleteJob = viewModelScope.launch {
            val refusal = refuse?.invoke() ?: run {
                saveJob?.cancelAndJoin()
                delete()
            }
            if (refusal != null) {
                deleting = false
                _outcome.value = refusal
            } else {
                _outcome.value = EditorOutcome.Deleted
            }
        }
        watch(deleteJob)
    }

    /** Keeps [busy] as [isBusy] says, now that [job] has started, and once it ends. */
    private fun watch(job: Job?) {
        _busy.value = isBusy
        job?.invokeOnCompletion { _busy.value = isBusy }
    }

    /**
     * Whether the screen may close now: not while a save or a delete runs, which closes it once it has written, with
     * what it did for the screen it returns to; left before, the write would go untold, and that screen would neither
     * show it nor restart the running profile with it. Once the screen may close, no save or delete starts any more.
     */
    fun leaveScreen(): Boolean {
        if (isBusy) return false
        left = true
        return true
    }

    /**
     * The screen has acted on [handled], the outcome it was shown: cleared, unless another one came meanwhile, as a save
     * that ends right after a delete was refused, which the screen then acts on in its turn.
     */
    fun onOutcomeHandled(handled: EditorOutcome? = _outcome.value) {
        if (handled != null) _outcome.compareAndSet(handled, null)
    }
}
