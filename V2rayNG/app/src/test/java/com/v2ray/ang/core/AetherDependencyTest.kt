package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class AetherDependencyTest {

    private val masque = ProfileItem.create(EConfigType.AETHER).apply { remarks = "warp"; aetherProtocol = AetherProtocol.MASQUE.type }
    private val masqueCopy = ProfileItem.create(EConfigType.AETHER).apply { remarks = "warp again"; aetherProtocol = AetherProtocol.MASQUE.type }
    private val wireguard = ProfileItem.create(EConfigType.AETHER).apply { remarks = "wg"; aetherProtocol = AetherProtocol.WIREGUARD.type }
    private val vless = ProfileItem.create(EConfigType.VLESS).apply { remarks = "vless"; server = "1.2.3.4"; serverPort = "443" }
    private val trojan = ProfileItem.create(EConfigType.TROJAN).apply { remarks = "trojan"; server = "5.6.7.8"; serverPort = "443" }

    private fun outbound(tag: String, type: CoreResolvedType, vararg profiles: ProfileItem) =
        CoreConfigContext.ResolvedOutbound(tag, profiles.first(), profiles.toList(), type)

    @Test
    fun aConfigurationWithoutAetherNeedsNoCore() {
        assertEquals(AetherDependency.None, AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, vless))))
        assertEquals(AetherDependency.None, AetherDependency.of(emptyList()))
    }

    @Test
    fun theSelectedAetherProfileIsTheDependency() {
        assertEquals(AetherDependency.Single(masque), AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, masque))))
    }

    @Test
    fun aChainMayHaveAetherAsItsEntryHopOnly() {
        // Chain profiles are stored exit first, entry last.
        assertEquals(
            AetherDependency.Single(masque),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, vless, masque)))
        )
        assertEquals(
            AetherDependency.NotEntryHop("proxy"),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, masque, vless)))
        )
        assertEquals(
            AetherDependency.NotEntryHop("proxy"),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, vless, masque, trojan)))
        )
    }

    @Test
    fun aRoutingTargetOrAGroupMemberMayBeAetherAnywhere() {
        assertEquals(
            AetherDependency.Single(wireguard),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, vless), outbound("warp", CoreResolvedType.NORMAL, wireguard)))
        )
        assertEquals(
            AetherDependency.Single(masque),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.POLICYGROUP, vless, masque, trojan)))
        )
    }

    @Test
    fun theSameSettingsUnderTwoNamesAreOneDependency() {
        assertEquals(
            AetherDependency.Single(masque),
            AetherDependency.of(
                listOf(outbound("proxy", CoreResolvedType.PROXYCHAIN, vless, masque), outbound("warp", CoreResolvedType.NORMAL, masqueCopy))
            )
        )
    }

    @Test
    fun twoDifferentAetherProfilesCannotShareOneCore() {
        assertEquals(
            AetherDependency.Conflicting,
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, masque), outbound("warp", CoreResolvedType.NORMAL, wireguard)))
        )
        assertEquals(
            AetherDependency.Conflicting,
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.POLICYGROUP, masque, wireguard)))
        )
    }
}
