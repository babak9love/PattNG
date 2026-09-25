package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
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

    @Test
    fun aCustomConfigurationWithoutACommandAsksForNoCore() {
        // SOCKS outbounds on the loopback address are not enough: only aetherCommand names a core.
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(socksTo("proxy", port = 20808), freedom)))
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom("""{"tag": "local", "protocol": "socks", "settings": {"address": "127.0.0.1", "port": 1080}}""")))
        // A JSON null at the key is no command either.
        assertEquals(AetherDependency.None, AetherDependency.ofCustom(custom(socksTo("proxy"), aetherCommand = "null")))
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
