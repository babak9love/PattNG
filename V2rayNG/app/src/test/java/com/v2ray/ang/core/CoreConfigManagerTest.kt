package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoreConfigManagerTest {

    private fun socks(address: String, port: Int) = V2rayConfig.OutboundBean(
        protocol = "socks",
        settings = V2rayConfig.OutboundBean.OutSettingsBean(address = address, port = port),
    )

    @Test
    fun onlyTheAetherOutboundsMoveToTheTestTunnelPort() {
        val aether = socks(AppConfig.LOOPBACK, AetherCoreManager.socksPort)
        val otherLocalSocks = socks(AppConfig.LOOPBACK, 1080)
        val remoteSocks = socks("10.0.0.1", AetherCoreManager.socksPort)
        val vless = V2rayConfig.OutboundBean(
            protocol = "vless",
            settings = V2rayConfig.OutboundBean.OutSettingsBean(address = "1.2.3.4", port = 443),
        )
        val bare = V2rayConfig.OutboundBean(protocol = "freedom")

        CoreConfigManager.rebindAetherOutbounds(listOf(aether, otherLocalSocks, remoteSocks, vless, bare), from = AetherCoreManager.socksPort, port = 41234)

        assertEquals(41234, aether.settings?.port)
        assertEquals(1080, otherLocalSocks.settings?.port)
        assertEquals(AetherCoreManager.socksPort, remoteSocks.settings?.port)
        assertEquals(443, vless.settings?.port)
    }

    @Test
    fun anAetherOutboundOnAPortOfItsOwnMovesFromThatPort() {
        val aether = socks(AppConfig.LOOPBACK, 20808)
        val defaultPort = socks(AppConfig.LOOPBACK, AetherCoreManager.socksPort)

        CoreConfigManager.rebindAetherOutbounds(listOf(aether, defaultPort), from = 20808, port = 41234)

        assertEquals(41234, aether.settings?.port)
        assertEquals(AetherCoreManager.socksPort, defaultPort.settings?.port)
    }

    @Test
    fun oneAetherOutboundKeepsTheSettingsACustomConfigurationNeeds() {
        fun aether() = socks(AppConfig.LOOPBACK, AetherCoreManager.socksPort).apply {
            settings?.aetherSettings = V2rayConfig.OutboundBean.OutSettingsBean.AetherSettingsBean(protocol = "masque")
        }
        val first = aether()
        val second = aether()
        val plain = socks(AppConfig.LOOPBACK, 1080)

        CoreConfigManager.keepOneAetherSettings(listOf(plain, first, second))

        assertNotNull(first.settings?.aetherSettings)
        assertNull(second.settings?.aetherSettings)
        assertNull(plain.settings?.aetherSettings)
        // The second one still dials the core the first one describes.
        assertEquals(AetherCoreManager.socksPort, second.settings?.port)
    }
}
