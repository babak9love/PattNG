package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The dependency on the core of [profile]. */
    private fun single(profile: ProfileItem) = AetherDependency.Single(AetherCore.of(profile))

    private fun coreOf(dependency: AetherDependency): AetherCore = (dependency as AetherDependency.Single).core

    @Test
    fun aConfigurationWithoutAetherNeedsNoCore() {
        assertEquals(AetherDependency.None, AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, vless))))
        assertEquals(AetherDependency.None, AetherDependency.of(emptyList()))
    }

    @Test
    fun theSelectedAetherProfileIsTheDependency() {
        assertEquals(single(masque), AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, masque))))
    }

    @Test
    fun aChainMayHaveAetherAsItsEntryHopOnly() {
        // Chain profiles are stored exit first, entry last.
        assertEquals(
            single(masque),
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
            single(wireguard),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, vless), outbound("warp", CoreResolvedType.NORMAL, wireguard)))
        )
        assertEquals(
            single(masque),
            AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.POLICYGROUP, vless, masque, trojan)))
        )
    }

    @Test
    fun theSameSettingsUnderTwoNamesAreOneDependency() {
        assertEquals(
            single(masque),
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
        assertEquals(single(elsewhere), AetherDependency.of(listOf(outbound("proxy", CoreResolvedType.NORMAL, elsewhere))))
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

    // ---- custom configurations, with the command line of the core at the top

    /** A custom configuration; [aetherCommand] is the JSON of the value, null for no key. */
    private fun custom(vararg outbounds: String, aetherCommand: String? = null): JsonObject {
        val command = aetherCommand?.let { "\"aetherCommand\": $it, " }.orEmpty()
        return JsonParser.parseString("""{$command"inbounds": [], "outbounds": [${outbounds.joinToString(",")}], "routing": {}}""").asJsonObject
    }

    /** [command] as the JSON string an aetherCommand holds. */
    private fun quoted(command: String) = "\"$command\""

    private fun socksTo(tag: String, port: Any = 10819, address: String = "127.0.0.1") =
        """{"tag": "$tag", "protocol": "socks", "settings": {"address": "$address", "port": $port}}"""

    private val freedom = """{"tag": "direct", "protocol": "freedom"}"""

    @Test
    fun aCustomConfigurationWithoutAnAetherCommandNeedsNoCore() {
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(freedom, socksTo("proxy"))))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(JsonParser.parseString("{}").asJsonObject))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(socksTo("proxy"), aetherCommand = "null")))
    }

    @Test
    fun theCoreOfACustomConfigurationIsItsCommandAsWritten() {
        val dependency = AetherDependency.ofCustom(
            custom(freedom, socksTo("proxy", port = 20808), aetherCommand = quoted("aether --gool --scan balanced --bind 127.0.0.1:20808"))
        )

        val core = coreOf(dependency)
        assertEquals(listOf("--gool", "--scan", "balanced", "--bind", "127.0.0.1:20808"), core.arguments)
        assertEquals(20808, core.port)
        assertEquals(AetherProtocol.GOOL, core.protocol)
    }

    @Test
    fun aCommandWithoutABindListensWhereTheOutboundsOfTheAppDialByDefault() {
        val core = coreOf(AetherDependency.ofCustom(custom(socksTo("proxy"), aetherCommand = quoted("aether --wg"))))
        assertEquals(AetherCoreManager.socksPort, core.port)
        assertEquals(listOf("--wg", "--bind", "127.0.0.1:10819"), core.arguments)
    }

    @Test
    fun theCommandHasToBeDialedByASocksOutbound() {
        val command = quoted("aether --wg --bind 127.0.0.1:20808")
        assertEquals(AetherDependency.NoOutbound(20808), AetherDependency.ofCustom(custom(freedom, aetherCommand = command)))
        assertEquals(AetherDependency.NoOutbound(20808), AetherDependency.ofCustom(custom(socksTo("proxy"), aetherCommand = command)))
        assertEquals(
            AetherDependency.NoOutbound(20808),
            AetherDependency.ofCustom(custom(socksTo("proxy", port = 20808, address = "10.0.0.2"), aetherCommand = command))
        )
        assertEquals(AetherDependency.NoOutbound(20808), AetherDependency.ofCustom(custom(socksTo("proxy", port = "\"20808\""), aetherCommand = command)))
        assertEquals(
            AetherDependency.NoOutbound(20808),
            AetherDependency.ofCustom(custom("""{"protocol": "http", "settings": {"address": "127.0.0.1", "port": 20808}}""", aetherCommand = command))
        )
        // The other outbounds take no part, wherever they dial.
        assertEquals(
            20808,
            coreOf(AetherDependency.ofCustom(custom(socksTo("local", port = 1080), freedom, socksTo("warp", port = 20808), aetherCommand = command))).port
        )
    }

    @Test
    fun anAetherCommandThatIsNoCommandLineIsReported() {
        fun written(aetherCommand: String) = AetherDependency.ofCustom(custom(socksTo("proxy"), aetherCommand = aetherCommand))
        assertEquals(AetherDependency.UnusableCommand("42"), written("42"))
        assertEquals(AetherDependency.UnusableCommand("""{"protocol":"wg"}"""), written("""{"protocol": "wg"}"""))
        assertEquals(AetherDependency.UnusableCommand("""["aether","--wg"]"""), written("""["aether", "--wg"]"""))
        assertEquals(AetherDependency.UnusableCommand(""), written(quoted("")))
        assertEquals(AetherDependency.UnusableCommand("aether"), written(quoted("aether")))
        // A listener whose port cannot be read: the port is what ties the outbounds to the core.
        assertEquals(AetherDependency.UnusableCommand("aether --wg --bind 10819"), written(quoted("aether --wg --bind 10819")))
        // What was written reaches the screen, not the log: a mistyped command can carry a secret.
        assertFalse(written(quoted("aether --access-secret s3cret --bind 10819")).toString().contains("s3cret"))
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
        val core = AetherCore.of(profile)

        val reimported = coreOf(AetherDependency.ofCustom(custom(socksTo("proxy", port = core.port), freedom, aetherCommand = quoted(core.command))))

        assertEquals(core, reimported)
        assertEquals("127.0.0.1:20808", AetherCoreManager.bindAddressOf(reimported.arguments))
    }

    @Test
    fun aTestTunnelTakesOverTheCommandAndEveryOutboundDialingIt() {
        val config = custom(
            socksTo("proxy", port = 20808),
            socksTo("same-core", port = 20808),
            socksTo("local", port = 1080),
            socksTo("remote", port = 20808, address = "10.0.0.2"),
            """{"tag": "vless", "protocol": "vless", "settings": {"address": "127.0.0.1", "port": 20808}}""",
            aetherCommand = quoted("aether --bind 127.0.0.1:20808 --wg"),
        )

        AetherDependency.rebindCustom(config, from = 20808, port = 41234)

        val ports = config.getAsJsonArray("outbounds").map { it.asJsonObject.getAsJsonObject("settings").get("port").asInt }
        assertEquals(listOf(41234, 41234, 1080, 20808, 20808), ports)
        // The configuration still says which core it dials.
        assertEquals("aether --wg --bind 127.0.0.1:41234", config.get("aetherCommand").asString)
        assertEquals(41234, coreOf(AetherDependency.ofCustom(config)).port)
    }

    // ---- the form before aetherCommand: aetherSettings in a SOCKS outbound

    private fun aetherOutbound(tag: String, port: Any? = 10819, address: String = "127.0.0.1", aetherSettings: String = "{}") =
        """{"tag": "$tag", "protocol": "socks", "settings": {"address": "$address", "port": $port, "aetherSettings": $aetherSettings}}"""

    private val plainSocks = """{"tag": "local", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 1080}}"""

    @Test
    fun aConfigurationWrittenWithAetherSettingsStillAsksForItsCore() {
        val settings = """{"address": "188.114.96.77", "port": "443", "protocol": "wg", "scan": "balanced", "noize": "aggressive", "ip": "both"}"""
        val core = coreOf(AetherDependency.ofCustom(custom(freedom, aetherOutbound("proxy", port = 20808, aetherSettings = settings))))
        // The core listens where that outbound dials.
        assertEquals(20808, core.port)
        assertEquals(
            listOf(
                "--bind", "127.0.0.1:20808", "--protocol", "wg", "--scan", "balanced", "--noize", "aggressive", "--ip", "both",
                "--peer", "188.114.96.77:443", "--quick-reconnect",
            ),
            core.arguments
        )

        // Left to the scanner, on the default port.
        val scanned = coreOf(AetherDependency.ofCustom(custom(aetherOutbound("proxy", aetherSettings = """{"protocol": "gool"}"""))))
        assertEquals(AetherCoreManager.socksPort, scanned.port)
        assertTrue("--wiw-scan" in scanned.arguments)

        // A JSON null is a key left out, and only a SOCKS outbound reaches the core.
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(aetherOutbound("proxy", aetherSettings = "null"))))
        assertEquals(
            AetherDependency.None,
            AetherDependency.ofCustom(custom("""{"protocol": "http", "settings": {"address": "127.0.0.1", "port": 10819, "aetherSettings": {}}}"""))
        )
    }

    @Test
    fun anAetherCommandComesBeforeAetherSettings() {
        val both = custom(
            aetherOutbound("proxy", port = 20808, aetherSettings = """{"protocol": "masque"}"""),
            aetherCommand = quoted("aether --wg --bind 127.0.0.1:20808"),
        )
        assertEquals(listOf("--wg", "--bind", "127.0.0.1:20808"), coreOf(AetherDependency.ofCustom(both)).arguments)
    }

    @Test
    fun socksOutboundsWithoutAetherSettingsAreNotAetherOutbounds() {
        // Wherever they dial and however they are written, they take no part in choosing the core:
        // they are neither counted nor held to what an Aether outbound has to look like.
        val others = arrayOf(
            plainSocks,
            """{"tag": "remote", "protocol": "socks", "settings": {"address": "203.0.113.9", "port": 1080, "user": "u", "pass": "p"}}""",
            """{"tag": "legacy", "protocol": "socks", "settings": {"servers": [{"address": "10.0.0.2", "port": 1080}]}}""",
            """{"tag": "odd", "protocol": "socks", "settings": {"address": "localhost", "port": "1080"}}""",
            """{"tag": "bare", "protocol": "socks"}""",
        )

        val dependency = AetherDependency.ofCustom(
            custom(*others, aetherOutbound("warp", port = 20808, aetherSettings = """{"protocol": "wg"}"""), freedom)
        )
        val core = coreOf(dependency)
        assertEquals(20808, core.port)
        assertEquals(AetherProtocol.WIREGUARD, core.protocol)

        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(*others, freedom)))
    }

    @Test
    fun severalOutboundsOfACustomConfigurationMayAskForTheSameCore() {
        val wg = """{"address": "188.114.96.77", "port": 443, "protocol": "wg"}"""
        val twice = AetherDependency.ofCustom(
            custom(aetherOutbound("proxy", port = 20808, aetherSettings = wg), freedom, aetherOutbound("warp", port = 20808, aetherSettings = wg))
        )
        assertEquals(20808, coreOf(twice).port)

        // The same core written in other words: what counts is what the core would be started with.
        val reworded = """{"port": "443", "address": "188.114.96.77", "protocol": "WG", "scan": "balanced", "fragment": false}"""
        val same = AetherDependency.ofCustom(
            custom(aetherOutbound("proxy", port = 20808, aetherSettings = wg), aetherOutbound("warp", port = 20808, aetherSettings = reworded))
        )
        assertTrue(same is AetherDependency.Single)
    }

    @Test
    fun outboundsThroughTheSameCoreMayDifferInWhatXrayDoesWithThem() {
        // What sets them apart belongs to Xray, such as targetStrategy; the core behind them is one.
        fun outbound(tag: String, targetStrategy: String) =
            """{"tag": "$tag", "protocol": "socks", "targetStrategy": "$targetStrategy", "settings": {"address": "127.0.0.1", "port": 20808,""" +
                """ "aetherSettings": {"protocol": "masque", "noize": "aggressive"}}}"""

        val dependency = AetherDependency.ofCustom(custom(outbound("warp", "AsIs"), outbound("warp-ip", "UseIPv4v6"), freedom))

        assertEquals("127.0.0.1:20808", AetherCoreManager.bindAddressOf(coreOf(dependency).arguments))
    }

    @Test
    fun aCustomConfigurationCannotAskForTwoCores() {
        // Other settings need another core.
        assertEquals(
            AetherDependency.SeveralCores,
            AetherDependency.ofCustom(custom(aetherOutbound("proxy"), aetherOutbound("warp", aetherSettings = """{"protocol": "wg"}""")))
        )
        // So does the same tunnel behind another port: one core listens on one port.
        assertEquals(
            AetherDependency.SeveralCores,
            AetherDependency.ofCustom(custom(aetherOutbound("proxy"), aetherOutbound("warp", port = 20808)))
        )
        // A problem in any of them is reported before they are compared.
        assertEquals(
            AetherDependency.NoListener,
            AetherDependency.ofCustom(custom(aetherOutbound("proxy"), aetherOutbound("warp", address = "10.0.0.2")))
        )
        assertEquals(
            AetherDependency.UnusableSettings(AetherFmt.Settings.Unknown("noise")),
            AetherDependency.ofCustom(custom(aetherOutbound("proxy"), aetherOutbound("warp", aetherSettings = """{"noise": "off"}""")))
        )
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
        assertEquals(41234, coreOf(AetherDependency.ofCustom(config)).port)
    }

    private fun withInbounds(vararg inbounds: String) = """{"inbounds": [${inbounds.joinToString(",")}], "outbounds": []}"""

    @Test
    fun anInboundOnTheAetherPortIsFoundBeforeTheCoreIsStarted() {
        // What the app builds: the local proxy with a port, and a tun inbound without one.
        val built = withInbounds(
            """{"tag": "socks", "protocol": "socks", "listen": "127.0.0.1", "port": 10808}""",
            """{"tag": "tun", "protocol": "tun", "settings": {"mtu": 1500}}""",
        )
        assertTrue(AetherDependency.inboundListensOn(built, 10808))
        assertFalse(AetherDependency.inboundListensOn(built, 10819))

        // The local proxy moved onto the default Aether port, or picked there at random.
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": 10819}"""), 10819))
    }

    @Test
    fun theInboundPortsOfACustomConfigurationAreReadInEveryFormXrayReads() {
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": "20808"}"""), 20808))
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "dokodemo-door", "port": "20000-21000"}"""), 20808))
        assertTrue(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "http", "port": "53, 443 ,20800-20810"}"""), 20808))
        assertFalse(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "http", "port": "53,443,20800-20807"}"""), 20808))
        assertFalse(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": "20000-21000"}"""), 21001))
        // A port taken from the environment is not known here, and it is not a reason to refuse the start.
        assertFalse(AetherDependency.inboundListensOn(withInbounds("""{"protocol": "socks", "port": "env:PORT"}"""), 20808))
    }

    @Test
    fun aConfigurationWithoutReadableInboundsCollidesWithNothing() {
        assertFalse(AetherDependency.inboundListensOn("""{"outbounds": []}""", 10819))
        assertFalse(AetherDependency.inboundListensOn("""{"inbounds": {"port": 10819}}""", 10819))
        assertFalse(AetherDependency.inboundListensOn(withInbounds("10819", """{"port": null}""", """{"port": [10819]}""", """{"port": true}"""), 10819))
        assertFalse(AetherDependency.inboundListensOn("", 10819))
        assertFalse(AetherDependency.inboundListensOn("not json {", 10819))
        assertFalse(AetherDependency.inboundListensOn("[]", 10819))
    }
}
