package com.v2ray.ang.ui.server

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProxyChainMembersTest {
    @Test
    fun removalFollowsThePendingKeyAfterReordering() {
        val pendingKey = "two"
        val members = listOf("Two", "One", "Three")
        val keys = listOf("two", "one", "three")

        val (remainingMembers, remainingKeys) = withoutProxyChainMember(members, keys, pendingKey)

        assertEquals(listOf("One", "Three"), remainingMembers)
        assertEquals(listOf("one", "three"), remainingKeys)
        assertEquals(listOf("Two", "One", "Three"), members)
        assertEquals(listOf("two", "one", "three"), keys)
    }

    @Test
    fun duplicateNamesDoNotChangeWhichMemberIsRemoved() {
        val (members, keys) = withoutProxyChainMember(
            listOf("Same", "Same", "Other"),
            listOf("first", "second", "third"),
            "second",
        )

        assertEquals(listOf("Same", "Other"), members)
        assertEquals(listOf("first", "third"), keys)
    }

    @Test
    fun missingMemberLeavesBothListsUnchanged() {
        val members = listOf("One", "Three")
        val keys = listOf("one", "three")

        val (remainingMembers, remainingKeys) = withoutProxyChainMember(members, keys, "two")

        assertSame(members, remainingMembers)
        assertSame(keys, remainingKeys)
    }

    @Test
    fun emptyChainRemainsEmpty() {
        assertEquals(
            emptyList<String>() to emptyList<String>(),
            withoutProxyChainMember(emptyList(), emptyList(), "missing"),
        )
    }

    @Test
    fun blankMemberIsRemovedWithItsOwnKey() {
        assertEquals(
            listOf("One") to listOf("one"),
            withoutProxyChainMember(listOf("One", ""), listOf("one", "blank"), "blank"),
        )
    }

    @Test
    fun anAetherMemberIsAllowedAsTheFirstHopOnly() {
        assertNull(aetherChainProblem(listOf(EConfigType.AETHER, EConfigType.VLESS)))
        assertNull(aetherChainProblem(listOf(EConfigType.VLESS, EConfigType.TROJAN)))
        assertNull(aetherChainProblem(listOf(null, EConfigType.VLESS)))
        assertEquals(AetherChainProblem.NOT_FIRST, aetherChainProblem(listOf(EConfigType.VLESS, EConfigType.AETHER)))
        assertEquals(AetherChainProblem.NOT_FIRST, aetherChainProblem(listOf(EConfigType.VLESS, EConfigType.AETHER, EConfigType.TROJAN)))
        assertEquals(AetherChainProblem.MORE_THAN_ONE, aetherChainProblem(listOf(EConfigType.AETHER, EConfigType.AETHER)))
    }
}
