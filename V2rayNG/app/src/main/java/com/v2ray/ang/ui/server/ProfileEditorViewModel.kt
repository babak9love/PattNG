package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.base.EditorViewModel

/**
 * PattNG: the save and the delete of the editor of a profile made of other profiles, a proxy chain or a policy group,
 * see [EditorViewModel]. The profile is stored as [guid]: the one the screen was opened on, or none for a new one until
 * its first save stores it, which a later save writes over. A new one goes into the subscription [subscriptionId], when
 * the screen was opened in one.
 */
abstract class ProfileEditorViewModel(
    application: Application,
    protected val source: ProfileEditorSource,
    private var guid: String,
    private val subscriptionId: String?,
) : EditorViewModel(application) {

    /** Stores the profile, of [type], with [edit] made on it, see [ProfileEditorSource.saveProfile], and keeps the guid it is stored as. */
    protected suspend fun store(type: EConfigType, edit: (ProfileItem) -> Unit): EditorOutcome.Saved {
        guid = source.saveProfile(guid, type) { config ->
            edit(config)
            if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
                config.subscriptionId = subscriptionId
            }
        }
        return EditorOutcome.Saved(guid)
    }

    /**
     * Deletes the profile, off the main thread, unless it is the profile the app runs on, which is told rather than
     * deleted; a save that runs stops first, see [EditorViewModel.launchDelete]. A new one, never stored, has none to
     * delete.
     */
    fun delete() {
        val stored = guid.takeIf { it.isNotEmpty() } ?: return
        launchDelete {
            if (source.isSelected(stored)) {
                EditorOutcome.Refused(R.string.toast_action_not_allowed)
            } else {
                source.deleteProfile(stored)
                EditorOutcome.Deleted
            }
        }
    }
}
