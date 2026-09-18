package com.v2ray.ang.ui.server

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

/** Why the Aether member of a chain cannot be where it is; null when the members are fine. */
internal enum class AetherChainProblem {
    /** An Aether member after the first hop: it would have to dial through another hop, which it cannot. */
    NOT_FIRST,

    /** More than one Aether member: one core serves one profile. */
    MORE_THAN_ONE,
}

/**
 * Members in the order of the editor, the first one dialing the internet directly. An Aether member
 * can only be that first hop: its outbound reaches the core on the loopback address and nothing else.
 */
internal fun aetherChainProblem(memberTypes: List<EConfigType?>): AetherChainProblem? {
    val positions = memberTypes.withIndex().filter { it.value == EConfigType.AETHER }.map { it.index }
    return when {
        positions.size > 1 -> AetherChainProblem.MORE_THAN_ONE
        positions.singleOrNull()?.let { it != 0 } == true -> AetherChainProblem.NOT_FIRST
        else -> null
    }
}
