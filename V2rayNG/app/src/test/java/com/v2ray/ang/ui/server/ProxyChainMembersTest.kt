package com.v2ray.ang.ui.server

import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyChainMembersTest {
    @Test
    fun aChainIsSavedWhenEachMemberNamesOneProfileAndOneAtMostIsAether() {
        val profiles = mapOf(
            "entry" to ProfileItem.create(EConfigType.VLESS),
            "warp" to ProfileItem.create(EConfigType.AETHER),
            "exit" to ProfileItem.create(EConfigType.TROJAN),
        )
        assertNull(proxyChainProblem(listOf("entry", "warp", "exit")) { name -> profiles[name]?.let { ByName.One(it) } ?: ByName.None })
    }

    @Test
    fun aMemberNoProfileHasAnyMoreOrSeveralHaveIsToldByItsName() {
        val found = mapOf<String, ByName<ProfileItem>>(
            "entry" to ByName.One(ProfileItem.create(EConfigType.VLESS)),
            "twice" to ByName.Several,
        )
        fun problem(vararg members: String) = proxyChainProblem(members.toList()) { found[it] ?: ByName.None }
        // The first of the members that cannot be told is named, as the chain names it when it runs.
        assertEquals(ProxyChainProblem.SameName("twice"), problem("entry", "twice", "gone"))
        assertEquals(ProxyChainProblem.NotFound("gone"), problem("entry", "gone", "twice"))
        assertEquals(ProxyChainProblem.SameName("twice"), problem("entry", "twice"))
        // The same name the start names for the chain.
        for (members in listOf(listOf("entry", "twice", "gone"), listOf("gone", "entry", "twice"))) {
            val atStart = CoreConfigContextBuilder.proxyChainHops(members) { found[it] ?: ByName.None }.second!!
            val expected = if (atStart.several) ProxyChainProblem.SameName(atStart.name) else ProxyChainProblem.NotFound(atStart.name)
            assertEquals(expected, problem(*members.toTypedArray()), members.toString())
        }
    }

    @Test
    fun aSecondAetherMemberIsTold() {
        val found = mapOf<String, ByName<ProfileItem>>(
            "warp" to ByName.One(ProfileItem.create(EConfigType.AETHER)),
            "warp 2" to ByName.One(ProfileItem.create(EConfigType.AETHER)),
            "entry" to ByName.One(ProfileItem.create(EConfigType.VLESS)),
        )
        assertEquals(ProxyChainProblem.SecondAether, proxyChainProblem(listOf("warp", "entry", "warp 2")) { found.getValue(it) })
    }

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
    fun anAetherMemberCanStandAnywhereButOnlyOnce() {
        assertFalse(hasSecondAetherMember(listOf(EConfigType.AETHER, EConfigType.VLESS)))
        assertFalse(hasSecondAetherMember(listOf(EConfigType.VLESS, EConfigType.AETHER)))
        assertFalse(hasSecondAetherMember(listOf(EConfigType.VLESS, EConfigType.AETHER, EConfigType.TROJAN)))
        assertFalse(hasSecondAetherMember(listOf(EConfigType.VLESS, EConfigType.TROJAN)))
        assertFalse(hasSecondAetherMember(listOf(null, EConfigType.VLESS)))
        assertTrue(hasSecondAetherMember(listOf(EConfigType.AETHER, EConfigType.AETHER)))
        assertTrue(hasSecondAetherMember(listOf(EConfigType.AETHER, EConfigType.VLESS, EConfigType.AETHER)))
    }
}
