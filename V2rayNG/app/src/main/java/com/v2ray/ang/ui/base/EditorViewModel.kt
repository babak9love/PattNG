package com.v2ray.ang.ui.base

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
 * copy at the next tap. Here one runs at a time, nothing starts once a delete has, and the [outcome] reaches whichever
 * activity shows the screen when it comes. A view model of a screen keeps what its saves stored as, for the next one to
 * write over. [onScreenLeft] stops what has not written yet.
 */
abstract class EditorViewModel(application: Application) : BaseViewModel(application) {

    private val _outcome = MutableStateFlow<EditorOutcome?>(null)

    /** The outcome the screen has not acted on yet, see [onOutcomeHandled]; null while there is none. */
    val outcome: StateFlow<EditorOutcome?> = _outcome.asStateFlow()

    private var job: Job? = null
    private var deleting = false
    private var left = false

    /** Whether a save or a delete runs. */
    val isBusy: Boolean
        get() = job?.isActive == true

    /**
     * Runs [save], unless a save or a delete runs, a delete has started, or the screen is left. What it ends with goes
     * to [outcome]; null, as when the screen shows a field's error itself, goes nowhere.
     */
    protected fun launchSave(save: suspend () -> EditorOutcome?) {
        if (isBusy || deleting || left) return
        job = viewModelScope.launch { save()?.let { _outcome.value = it } }
    }

    /** Runs [delete], unless a save or a delete runs, a delete has started, or the screen is left; then [outcome] is [EditorOutcome.Deleted]. */
    protected fun launchDelete(delete: suspend () -> Unit) {
        if (isBusy || deleting || left) return
        deleting = true
        job = viewModelScope.launch {
            delete()
            _outcome.value = EditorOutcome.Deleted
        }
    }

    /**
     * The screen is left: the save or the delete that runs stops unless it writes already, so nothing is written
     * after the screen is gone, and none starts any more.
     */
    fun onScreenLeft() {
        left = true
        job?.cancel()
    }

    /** The screen has acted on [outcome]. */
    fun onOutcomeHandled() {
        _outcome.value = null
    }
}
