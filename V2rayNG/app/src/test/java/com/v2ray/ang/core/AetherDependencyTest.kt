package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun theSameTunnelBehindTwoListenPortsNeedsTwoCores() {
        val elsewhere = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "warp on 20808"; aetherProtocol = AetherProtocol.MASQUE.type; aetherListenPort = "20808"
        }
        assertEquals(AetherDependency.Single(elsewhere), AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, elsewhere))))
        assertEquals(
            AetherDependency.Conflicting,
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, masque), outbound("warp", CoreResolvedType.NORMAL, elsewhere)))
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

    private fun custom(vararg outbounds: String): JsonObject =
        JsonParser.parseString("""{"inbounds": [], "outbounds": [${outbounds.joinToString(",")}], "routing": {}}""").asJsonObject

    private fun aetherOutbound(tag: String, port: Any? = 10819, address: String = "127.0.0.1", aetherSettings: String = "{}") =
        """{"tag": "$tag", "protocol": "socks", "settings": {"address": "$address", "port": $port, "aetherSettings": $aetherSettings}}"""

    private val freedom = """{"tag": "direct", "protocol": "freedom"}"""
    private val plainSocks = """{"tag": "local", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 1080}}"""

    @Test
    fun aCustomConfigurationWithoutAetherSettingsNeedsNoCore() {
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(freedom, plainSocks)))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(JsonParser.parseString("{}").asJsonObject))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(aetherOutbound("proxy", aetherSettings = "null"))))
        // Only a SOCKS outbound reaches the core, so the key means nothing anywhere else.
        assertEquals(
            AetherDependency.None,
            AetherDependency.ofCustom(custom("""{"protocol": "http", "settings": {"address": "127.0.0.1", "port": 10819, "aetherSettings": {}}}"""))
        )
    }

    @Test
    fun theCoreOfACustomConfigurationListensWhereItsOutboundDials() {
        val settings = """{"address": "188.114.96.77", "port": "443", "protocol": "wg", "scan": "balanced", "noize": "aggressive", "ip": "both"}"""
        val dependency = AetherDependency.ofCustom(custom(freedom, aetherOutbound("proxy", port = 20808, aetherSettings = settings)))

        val profile = (dependency as AetherDependency.Single).profile
        assertEquals(20808, AetherCoreManager.listenPort(profile))
        assertEquals(
            listOf(
                "--bind", "127.0.0.1:20808", "--protocol", "wg", "--scan", "balanced", "--noize", "aggressive", "--ip", "both",
                "--peer", "188.114.96.77:443", "--quick-reconnect", "--log-level", "info",
            ),
            AetherCoreManager.buildArguments(profile, AetherCoreManager.listenPort(profile))
        )
    }

    @Test
    fun aCustomConfigurationLeftToTheScannerStillStartsItsCore() {
        val single = AetherDependency.ofCustom(custom(aetherOutbound("proxy", aetherSettings = """{"protocol": "gool"}"""))) as AetherDependency.Single
        assertEquals(AetherCoreManager.socksPort, AetherCoreManager.listenPort(single.profile))
        // The default port is stored the way the editor stores it.
        assertNull(single.profile.aetherListenPort)
        assertTrue("--wiw-scan" in AetherCoreManager.buildArguments(single.profile, AetherCoreManager.socksPort))
    }

    @Test
    fun theFullConfigurationOfAProfileAsksForTheSameCore() {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTransport = "h2"
            aetherFragment = true
            aetherFragmentSize = "16-32"
            server = "162.159.198.1"
            serverPort = "443"
            aetherListenPort = "20808"
        }
        val exported = aetherOutbound("proxy", port = AetherCoreManager.listenPort(profile), aetherSettings = JsonUtil.toJson(AetherFmt.toSettings(profile)))

        val reimported = (AetherDependency.ofCustom(custom(exported, freedom)) as AetherDependency.Single).profile
        assertEquals(
            AetherCoreManager.buildArguments(profile, AetherCoreManager.listenPort(profile)),
            AetherCoreManager.buildArguments(reimported, AetherCoreManager.listenPort(reimported))
        )
        assertEquals("127.0.0.1:20808", AetherCoreManager.bindAddressOf(AetherCoreManager.buildArguments(reimported, AetherCoreManager.listenPort(reimported))))
    }

    @Test
    fun aCustomConfigurationMayAskForOneCoreOnly() {
        assertEquals(
            AetherDependency.SeveralCores,
            AetherDependency.ofCustom(custom(aetherOutbound("proxy"), aetherOutbound("warp", port = 20808, aetherSettings = """{"protocol": "wg"}""")))
        )
        // Even the same core written twice: the rule is one outbound, which needs no comparing.
        assertEquals(AetherDependency.SeveralCores, AetherDependency.ofCustom(custom(aetherOutbound("proxy"), aetherOutbound("warp"))))
    }

    @Test
    fun theOutboundOfACustomConfigurationHasToDialTheCore() {
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(aetherOutbound("proxy", address = "10.0.0.2"))))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(aetherOutbound("proxy", address = "localhost"))))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(aetherOutbound("proxy", port = 0))))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(aetherOutbound("proxy", port = 65536))))
        assertEquals(AetherDependency.NoListener, AetherDependency.ofCustom(custom(aetherOutbound("proxy", port = "\"10819\""))))
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(custom("""{"protocol": "socks", "settings": {"aetherSettings": {}}}"""))
        )
    }

    @Test
    fun aetherSettingsThatCannotStartACoreAreReported() {
        assertEquals(
            AetherDependency.UnusableSettings(AetherFmt.Settings.Unknown("protocol: wireguard")),
            AetherDependency.ofCustom(custom(aetherOutbound("proxy", aetherSettings = """{"protocol": "wireguard"}""")))
        )
        assertEquals(
            AetherDependency.UnusableSettings(AetherFmt.Settings.Refused(AetherFmt.Problem.INVALID_PEER)),
            AetherDependency.ofCustom(custom(aetherOutbound("proxy", aetherSettings = """{"address": "example.com", "port": 443}""")))
        )
        assertEquals(
            AetherDependency.UnusableSettings(AetherFmt.Settings.Unknown("true")),
            AetherDependency.ofCustom(custom(aetherOutbound("proxy", aetherSettings = "true")))
        )
    }

    @Test
    fun aTestTunnelTakesOverEveryOutboundDialingTheCore() {
        val config = custom(
            aetherOutbound("proxy", port = 20808),
            """{"tag": "same-core", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 20808}}""",
            plainSocks,
            """{"tag": "remote", "protocol": "socks", "settings": {"address": "10.0.0.2", "port": 20808}}""",
            """{"tag": "vless", "protocol": "vless", "settings": {"address": "127.0.0.1", "port": 20808}}""",
        )

        AetherDependency.rebindCustom(config, from = 20808, port = 41234)

        val ports = config.getAsJsonArray("outbounds").map { it.asJsonObject.getAsJsonObject("settings").get("port").asInt }
        assertEquals(listOf(41234, 41234, 1080, 20808, 20808), ports)
        // The core of the rebound configuration is still the one its aetherSettings describe.
        assertEquals(41234, AetherCoreManager.listenPort((AetherDependency.ofCustom(config) as AetherDependency.Single).profile))
    }
}
