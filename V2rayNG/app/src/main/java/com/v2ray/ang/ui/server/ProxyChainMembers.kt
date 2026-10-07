package com.v2ray.ang.ui.server

import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Removes one draft member and its row key together, resolving its current position at confirmation. */
internal fun withoutProxyChainMember(
    members: List<String>,
    memberKeys: List<String>,
    memberKey: String,
): Pair<List<String>, List<String>> {
    val index = memberKeys.indexOf(memberKey)
    if (index < 0) return members to memberKeys

    return members.toMutableList().also { it.removeAt(index) } to
        memberKeys.toMutableList().also { it.removeAt(index) }
}

/**
 * True when [memberTypes], the types of a chain's members, hold more than one Aether profile. One can
 * stand anywhere in the chain; a second would need a core of its own, and one core runs at a time.
 */
internal fun hasSecondAetherMember(memberTypes: List<EConfigType?>): Boolean =
    memberTypes.count { it == EConfigType.AETHER } > 1

/** PattNG: why a proxy chain cannot be saved with its members, see [proxyChainProblem]. */
internal sealed interface ProxyChainProblem {
    /** No profile that can be a hop is named [name], or none any more. */
    data class NotFound(val name: String) : ProxyChainProblem

    /** Several are named [name], and the chain could not tell the one meant. */
    data class SameName(val name: String) : ProxyChainProblem

    /** A second Aether member, see [hasSecondAetherMember]. */
    data object SecondAether : ProxyChainProblem
}

/**
 * PattNG: why a chain of [members], the names of its profiles in its order, cannot be saved, as the chain finds its
 * hops when it runs, see [CoreConfigContextBuilder.proxyChainHops]: the first of the names that [find] finds no profile
 * for, or several, see [ByName], or a second Aether member. Null when it can. The previous and the next profile of a
 * subscription, which chain each of its profiles, are checked alike, in the order the chain finds them.
 */
internal fun proxyChainProblem(members: List<String>, find: (String) -> ByName<ProfileItem>): ProxyChainProblem? {
    val (hops, unresolved) = CoreConfigContextBuilder.proxyChainHops(members, find)
    unresolved?.let { return if (it.several) ProxyChainProblem.SameName(it.name) else ProxyChainProblem.NotFound(it.name) }
    return ProxyChainProblem.SecondAether.takeIf { hasSecondAetherMember(hops.map { it.configType }) }
}
