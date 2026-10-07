package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.dto.entities.ProfileItem

/**
 * PattNG: the save and the delete of the editor of a profile the screen builds whole from what it shows, as a VLESS or
 * an Aether one, see [BaseServerActivity] and [ProfileEditorViewModel]: unlike a proxy chain's, whose screen sets a few
 * fields on the profile as stored, the profile is stored as built.
 */
class ServerEditorViewModel(
    application: Application,
    source: ProfileEditorSource,
    guid: String,
    subscriptionId: String?,
) : ProfileEditorViewModel(application, source, guid, subscriptionId) {

    /** Stores [profile], which the screen has built and checked, off the main thread. */
    fun save(profile: ProfileItem) = launchSave { store(profile) }
}
