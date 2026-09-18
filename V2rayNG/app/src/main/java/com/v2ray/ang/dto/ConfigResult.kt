package com.v2ray.ang.dto

import com.v2ray.ang.dto.entities.ProfileItem

data class ConfigResult(
    var status: Boolean,
    var guid: String? = null,
    var content: String = "",
    var errorMessage: String = "",
    /** True when [errorMessage] is a localized resource string meant for the screen. */
    var localizedError: Boolean = false,
    /** The Aether profile the configuration runs on, when it has an Aether outbound; the daemon starts its core. */
    var aetherProfile: ProfileItem? = null,
)
