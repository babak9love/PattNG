package com.v2ray.ang.ui.server

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUiStateTest {

    @Test
    fun aetherOptionsAreNormalizedToValuesTheDropdownsKnow() {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = "gool"
            aetherTransport = "h2"
            aetherScanMode = "from-a-newer-version"
            aetherObfuscation = null
            aetherIpVersion = "both"
        }

        val state = ServerUiState.from(profile)

        assertEquals(AetherProtocol.GOOL.type, state.aetherProtocol)
        assertEquals(AetherTransport.HTTP2.type, state.aetherTransport)
        assertEquals(AetherScanMode.BALANCED.type, state.aetherScanMode)
        assertEquals(AetherObfuscation.BALANCED.type, state.aetherObfuscation)
        assertEquals(AetherIpVersion.DUAL.type, state.aetherIpVersion)
    }

    @Test
    fun aNewAetherProfileStartsWithTheDefaultsAndNoPort() {
        val state = ServerUiState.from(ProfileItem.create(EConfigType.AETHER))

        assertEquals(AetherProtocol.MASQUE.type, state.aetherProtocol)
        assertEquals(AetherTransport.HTTP3.type, state.aetherTransport)
        assertEquals("", state.port)
    }

    @Test
    fun theTargetStrategyDefaultsToAsIsAndIsStoredOnlyWhenChanged() {
        val profile = ProfileItem.create(EConfigType.VLESS)

        val untouched = ServerUiState.from(profile)
        assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, untouched.targetStrategy)
        assertNull(untouched.toProfileItem(profile).targetStrategy)

        untouched.targetStrategy = "UseIPv4v6"
        assertEquals("UseIPv4v6", untouched.toProfileItem(profile).targetStrategy)

        val stored = ServerUiState.from(ProfileItem.create(EConfigType.AETHER).apply { targetStrategy = "ForceIP" })
        assertEquals("ForceIP", stored.targetStrategy)
    }
}
