package com.v2ray.ang.dto

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType

data class CoreConfigContext(
    val context: Context,
    val guid: String,
    val isCustom: Boolean = false,
    val resolvedOutbounds: List<ResolvedOutbound> = emptyList(),
    val routingDomainRules: List<RoutingDomainRule> = emptyList(),
    /** PattNG: a profile a routing rule sends to by a name no profile has, or several have; the session is refused for it. */
    val unresolvedRoutingTarget: UnresolvedName? = null,
) {
    data class ResolvedOutbound(
        val tag: String,
        val profile: ProfileItem,
        val resolvedProfiles: List<ProfileItem>,
        val resolvedType: CoreResolvedType,
        /**
         * PattNG: a hop the proxy chain, or the subscription around the profile, names that no profile has, or several
         * have; the configuration is refused for it.
         */
        val unresolvedHop: UnresolvedName? = null,
    )

    /** PattNG: a [name] that finds no profile, or, with [several], more than one, see [ByName]. */
    data class UnresolvedName(val name: String, val several: Boolean)

    data class RoutingDomainRule(
        val domain: List<String>,
        val outboundTag: String,
    )
}
