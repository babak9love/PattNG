package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.base.EditorViewModel

/**
 * PattNG: the save and the delete of the editor of a profile, see [EditorViewModel]: one made of other profiles, a
 * proxy chain or a policy group, one the screen builds whole, see [ServerEditorViewModel], or a custom one. The profile
 * is stored as [guid]: the one the screen was opened on, or none for a new one until its first save stores it, which a
 * later save writes over. A new one goes into the subscription [subscriptionId], when the screen was opened in one.
 */
abstract class ProfileEditorViewModel(
    application: Application,
    protected val source: ProfileEditorSource,
    guid: String,
    private val subscriptionId: String?,
) : EditorViewModel(application) {

    /** The guid the profile is stored as, see the class. */
    protected var guid: String = guid
        private set

    /**
     * Stores the profile, of [type], with [edit] made on it, and [raw] with it when given, see
     * [ProfileEditorSource.saveProfile], and keeps the guid it is stored as.
     */
    protected suspend fun store(type: EConfigType, raw: String? = null, edit: (ProfileItem) -> Unit): EditorOutcome.Saved {
        guid = source.saveProfile(guid, type, raw) { config ->
            edit(config)
            stampSubscription(config)
        }
        return EditorOutcome.Saved(guid)
    }

    /** Stores [profile], which the screen built whole, see [ProfileEditorSource.storeProfile], and keeps the guid it is stored as. */
    protected suspend fun store(profile: ProfileItem): EditorOutcome.Saved {
        stampSubscription(profile)
        guid = source.storeProfile(guid, profile)
        return EditorOutcome.Saved(guid)
    }

    private fun stampSubscription(config: ProfileItem) {
        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId
        }
    }

    /**
     * Deletes the profile, off the main thread, unless it is the profile the app runs on, which is told rather than
     * deleted, a save that runs going on; see [EditorViewModel.launchDelete]. A new one, never stored, has none to
     * delete.
     */
    fun delete() {
        val stored = guid.takeIf { it.isNotEmpty() } ?: return
        launchDelete(
            refuse = { EditorOutcome.Refused(R.string.toast_action_not_allowed).takeIf { source.isSelected(stored) } },
            delete = { source.deleteProfile(stored) },
        )
    }
}
