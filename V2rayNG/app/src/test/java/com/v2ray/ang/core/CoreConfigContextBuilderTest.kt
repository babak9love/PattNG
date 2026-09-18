package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun aGroupWithoutAetherIsUnchanged() {
        assertEquals(listOf(vless) to emptyList<ProfileItem>(), CoreConfigContextBuilder.withOneAetherProfile(listOf(vless)))
        assertEquals(emptyList<ProfileItem>() to emptyList<ProfileItem>(), CoreConfigContextBuilder.withOneAetherProfile(emptyList()))
    }
}
