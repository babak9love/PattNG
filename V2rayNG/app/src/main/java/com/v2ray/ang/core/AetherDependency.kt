package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType

/**
 * The Aether profile a configuration runs on. Every Aether outbound is a SOCKS connection to the
 * one core process the daemon starts, so a configuration can use one Aether profile, whether it is
 * the selected profile itself, the entry hop of a chain, a routing target or a policy-group member.
 * Two profiles count as the same one when the core would be started with the same arguments.
 *
 * In a chain the Aether hop can only be the entry hop, the one that dials the internet itself:
 * another hop can dial through it, but it cannot dial through anything, since its outbound only
 * reaches the core on the loopback address.
 */
sealed interface AetherDependency {

    /** No Aether outbound anywhere in the configuration. */
    data object None : AetherDependency

    /** Exactly one Aether profile; the daemon starts the core with it. */
    data class Single(val profile: ProfileItem) : AetherDependency

    /** Aether profiles with different settings, which one core cannot serve. */
    data object Conflicting : AetherDependency

    /** An Aether profile in a chain position other than the entry hop. */
    data class NotEntryHop(val chainTag: String) : AetherDependency

    companion object {

        /**
         * [outbounds] are the resolved outbounds of a configuration. A chain's profiles are in
         * reverse dial order: the first is the exit, the last is the entry hop.
         */
        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): AetherDependency {
            var found: ProfileItem? = null
            var foundArguments: List<String>? = null
            for (outbound in outbounds) {
                val profiles = outbound.resolvedProfiles
                for ((index, profile) in profiles.withIndex()) {
                    if (profile.configType != EConfigType.AETHER) continue
                    if (outbound.resolvedType == CoreResolvedType.PROXYCHAIN && index != profiles.lastIndex) {
                        return NotEntryHop(outbound.tag)
                    }
                    val arguments = AetherCoreManager.buildArguments(profile, AetherCoreManager.socksPort)
                    if (found == null) {
                        found = profile
                        foundArguments = arguments
                    } else if (arguments != foundArguments) {
                        return Conflicting
                    }
                }
            }
            return found?.let(::Single) ?: None
        }
    }
}
