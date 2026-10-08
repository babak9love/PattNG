package com.v2ray.ang.core

import com.v2ray.ang.R
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.GROUP_AS_FALLBACK
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.GROUP_AS_HOP
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.NO_SERVER
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.NOT_FOUND
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.SEVERAL
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreConfigContextBuilderTest {

    private fun aether(name: String, protocol: AetherProtocol) =
        ProfileItem.create(EConfigType.AETHER).apply { remarks = name; aetherProtocol = protocol.type }

    private val vless = ProfileItem.create(EConfigType.VLESS).apply { remarks = "vless"; server = "1.2.3.4"; serverPort = "443" }

    @Test
    fun aGroupKeepsItsFirstAetherProfileAndAnyWithTheSameSettings() {
        val first = aether("warp", AetherProtocol.MASQUE)
        val same = aether("warp again", AetherProtocol.MASQUE)
        val other = aether("wg", AetherProtocol.WIREGUARD)

        val (kept, leftOut) = CoreConfigContextBuilder.withOneAetherProfile(listOf(vless, first, other, same))

        assertEquals(listOf(vless, first, same), kept)
        assertEquals(listOf(other), leftOut)
    }

    @Test
    fun aGroupKeepsTheSameTunnelStoredWithAListenPortOfItsOwn() {
        // Profiles stored while each profile had a listen port of its own may carry one still; every core listens on the one port now.
        val first = aether("warp", AetherProtocol.MASQUE)
        val elsewhere = aether("warp on 20808", AetherProtocol.MASQUE).apply { aetherListenPort = "20808" }

        val (kept, leftOut) = CoreConfigContextBuilder.withOneAetherProfile(listOf(first, elsewhere))

        assertEquals(listOf(first, elsewhere), kept)
        assertEquals(emptyList<ProfileItem>(), leftOut)
    }

    @Test
    fun aGroupWithoutAetherIsUnchanged() {
        assertEquals(listOf(vless) to emptyList<ProfileItem>(), CoreConfigContextBuilder.withOneAetherProfile(listOf(vless)))
        assertEquals(emptyList<ProfileItem>() to emptyList<ProfileItem>(), CoreConfigContextBuilder.withOneAetherProfile(emptyList()))
    }

    @Test
    fun aChainFindsItsHopsByTheirNamesInItsOrder() {
        val entry = vless.copy(remarks = "entry")
        val profiles = mapOf("vless" to vless, "entry" to entry)
        val (hops, unresolved) = CoreConfigContextBuilder.proxyChainHops(listOf(" vless", "", "entry ")) { name, _ ->
            profiles[name]?.let { ByName.One(it) } ?: ByName.None
        }
        assertEquals(listOf(vless, entry), hops)
        assertNull(unresolved)
    }

    @Test
    fun aChainTellsTheFirstNameThatFindsNoProfileOrSeveral() {
        val found = mapOf<String, ByName<ProfileItem>>("vless" to ByName.One(vless), "twice" to ByName.Several)
        fun hops(vararg names: String) = CoreConfigContextBuilder.proxyChainHops(names.toList()) { name, _ -> found[name] ?: ByName.None }

        // Renamed or deleted: the chain does not run without that hop.
        assertEquals(listOf(vless) to CoreConfigContext.UnresolvedName("gone", NOT_FOUND), hops("gone", "vless", "twice"))
        // The name of two profiles: the chain does not guess which one it means.
        assertEquals(listOf(vless) to CoreConfigContext.UnresolvedName("twice", SEVERAL), hops("vless", "twice", "gone"))
    }

    @Test
    fun aChainGoesThroughAHopWithAnyServerAddressButRefusesOneWithNone() {
        val local = vless.copy(remarks = "local", server = "localhost")
        val nas = vless.copy(remarks = "nas", server = "nas")
        val blank = vless.copy(remarks = "blank", server = " ")
        val none = vless.copy(remarks = "none", server = null)
        val warp = aether("warp", AetherProtocol.WIREGUARD)
        val profiles = listOf(vless, local, nas, blank, none, warp).associateBy { it.remarks }
        fun hops(vararg names: String) = CoreConfigContextBuilder.proxyChainHops(names.toList()) { name, _ ->
            profiles[name]?.let { ByName.One(it) } ?: ByName.None
        }

        // Any address goes, a name without a dot as well, which Xray dials; an Aether profile has none of its own.
        assertEquals(listOf(local, nas, warp, vless) to null, hops("local", "nas", "warp", "vless"))
        // None at all: the chain is refused, rather than run without that hop as it used to.
        assertEquals(listOf(vless) to CoreConfigContext.UnresolvedName("blank", NO_SERVER), hops("vless", "blank", "none"))
        assertEquals(CoreConfigContext.UnresolvedName("none", NO_SERVER), hops("none").second)
        assertEquals(R.string.toast_profile_no_server, NO_SERVER.message)
    }

    @Test
    fun aHopOnlyAGroupHasIsToldAsAGroupsAndAProfileOfItsNameStillGoes() {
        fun profile(name: String, type: EConfigType) = ProfileItem.create(type).apply { remarks = name; server = "203.0.113.7" }
        val shared = profile("shared", EConfigType.TROJAN)
        val profiles = listOf(
            vless,
            profile("group", EConfigType.POLICYGROUP),
            profile("chain", EConfigType.PROXYCHAIN),
            profile("custom", EConfigType.CUSTOM),
            profile("shared", EConfigType.POLICYGROUP),
            shared,
        )
        fun hops(vararg names: String) = CoreConfigContextBuilder.proxyChainHops(names.toList()) { name, takes ->
            ByName.find(name, profiles.asSequence().filter(takes)) { it.remarks }
        }

        assertEquals(listOf(vless) to CoreConfigContext.UnresolvedName("group", GROUP_AS_HOP), hops("vless", "group"))
        assertEquals(R.string.toast_profile_group_not_hop, GROUP_AS_HOP.message)
        // A chain or a custom configuration cannot be a hop either, and is no group.
        assertEquals(CoreConfigContext.UnresolvedName("chain", NOT_FOUND), hops("chain").second)
        assertEquals(CoreConfigContext.UnresolvedName("custom", NOT_FOUND), hops("custom").second)
        // A group of the name stands aside for the one profile of it that can be a hop.
        assertEquals(listOf(shared) to null, hops("shared"))
    }

    @Test
    fun aChainKeepsACommaOrABackslashInsideTheNameOfAHop() {
        val names = listOf("US, Dallas", "Exit", """back\slash""", """a\,b""", "")
        assertEquals(names, ProfileItem.proxyChainMembersOf(ProfileItem.proxyChainProfilesOf(names)))
        // A name with neither is written as before, and a chain written before reads as it did.
        assertEquals("entry,warp,exit", ProfileItem.proxyChainProfilesOf(listOf("entry", "warp", "exit")))
        assertEquals(listOf("entry", " warp", "exit"), ProfileItem.proxyChainMembersOf("entry, warp,exit"))
        assertEquals(listOf("""a\b"""), ProfileItem.proxyChainMembersOf("""a\b"""))
        assertEquals(emptyList<String>(), ProfileItem.proxyChainMembersOf(null))
        assertEquals(emptyList<String>(), ProfileItem.proxyChainMembersOf(""))
        // The hops of such a chain are found by their whole names.
        val dallas = vless.copy(remarks = "US, Dallas")
        val profiles = listOf(vless, dallas).associateBy { it.remarks }
        val stored = ProfileItem.proxyChainProfilesOf(listOf("US, Dallas", "vless"))
        assertEquals(listOf(dallas, vless) to null, CoreConfigContextBuilder.proxyChainHops(ProfileItem.proxyChainMembersOf(stored)) { name, _ ->
            profiles[name]?.let { ByName.One(it) } ?: ByName.None
        })
    }

    @Test
    fun aHopIsAnyProfileButAChainAGroupOrACustomConfiguration() {
        val hops = listOf(
            EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN, EConfigType.SHADOWSOCKS, EConfigType.SOCKS,
            EConfigType.HTTP, EConfigType.WIREGUARD, EConfigType.HYSTERIA2, EConfigType.AETHER,
        )
        for (type in hops) {
            assertTrue(CoreConfigContextBuilder.takesAsHop(ProfileItem.create(type)), type.name)
        }
        for (type in listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN)) {
            assertFalse(CoreConfigContextBuilder.takesAsHop(ProfileItem.create(type)), type.name)
        }
    }

    @Test
    fun aRoutingRuleSendsToAnyProfileButACustomConfiguration() {
        for (type in EConfigType.entries.filter { it != EConfigType.CUSTOM }) {
            assertTrue(CoreConfigContextBuilder.takesAsRoutingTarget(ProfileItem.create(type)), type.name)
        }
        assertFalse(CoreConfigContextBuilder.takesAsRoutingTarget(ProfileItem.create(EConfigType.CUSTOM)))
    }

    @Test
    fun aGroupFallsBackToAnyProfileButAGroupOrACustomConfiguration() {
        val others = setOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP)
        for (type in EConfigType.entries.filter { it !in others }) {
            assertTrue(CoreConfigContextBuilder.takesAsFallback(ProfileItem.create(type)), type.name)
        }
        for (type in others) {
            assertFalse(CoreConfigContextBuilder.takesAsFallback(ProfileItem.create(type)), type.name)
        }
    }

    @Test
    fun aFallbackIsFoundAmongTheProfilesThatCanBeOneAndANameOnlyAGroupHasIsToldAsSuch() {
        fun profile(name: String, type: EConfigType) = ProfileItem.create(type).apply { remarks = name }
        val exit = profile("exit", EConfigType.VLESS)
        val shared = profile("shared", EConfigType.TROJAN)
        val profiles = listOf(
            exit,
            profile("group", EConfigType.POLICYGROUP),
            profile("custom", EConfigType.CUSTOM),
            profile("twice", EConfigType.VLESS),
            profile("twice", EConfigType.PROXYCHAIN),
            profile("shared", EConfigType.POLICYGROUP),
            shared,
        )
        val find = { name: String, takes: (ProfileItem) -> Boolean -> ByName.find(name, profiles.asSequence().filter(takes)) { it.remarks } }

        assertEquals(exit to null, CoreConfigContextBuilder.fallbackOf("exit", find))
        assertEquals(null to GROUP_AS_FALLBACK, CoreConfigContextBuilder.fallbackOf("group", find))
        assertEquals(null to NOT_FOUND, CoreConfigContextBuilder.fallbackOf("gone", find))
        // A custom configuration cannot be one either, and is no group.
        assertEquals(null to NOT_FOUND, CoreConfigContextBuilder.fallbackOf("custom", find))
        assertEquals(null to SEVERAL, CoreConfigContextBuilder.fallbackOf("twice", find))
        // A group of the name stands aside for the one profile of it that can be the fallback.
        assertEquals(shared to null, CoreConfigContextBuilder.fallbackOf("shared", find))
    }

    @Test
    fun aGroupFallbackNameIsReadTrimmedAndABlankOneNamesNone() {
        fun group(fallback: String?) = ProfileItem.create(EConfigType.POLICYGROUP).apply { policyGroupFallbackTag = fallback }
        assertEquals("exit", CoreConfigContextBuilder.fallbackNameOf(group("exit")))
        assertEquals("exit", CoreConfigContextBuilder.fallbackNameOf(group(" exit ")))
        assertNull(CoreConfigContextBuilder.fallbackNameOf(group("   ")))
        assertNull(CoreConfigContextBuilder.fallbackNameOf(group("")))
        assertNull(CoreConfigContextBuilder.fallbackNameOf(group(null)))
    }
}
