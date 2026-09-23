package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherCoreTest {

    private fun profile(listen: String? = null, block: ProfileItem.() -> Unit = {}) =
        ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = AetherProtocol.WIREGUARD.type
            aetherListenPort = listen
            block()
        }

    private val pinned = profile(listen = "20808") { server = "188.114.96.77"; serverPort = "443" }

    @Test
    fun theCoreOfAProfileIsItsSettingsOnItsPortWithoutALogLevel() {
        val core = AetherCore.of(pinned)
        assertEquals(
            listOf(
                "--bind", "127.0.0.1:20808", "--protocol", "wg", "--scan", "balanced", "--noize", "balanced", "--ip", "v4",
                "--peer", "188.114.96.77:443", "--quick-reconnect",
            ),
            core.arguments
        )
        assertEquals(20808, core.port)
        assertEquals(AetherProtocol.WIREGUARD, core.protocol)
        assertEquals(AetherCoreManager.socksPort, AetherCore.of(profile()).port)
    }

    @Test
    fun theCommandOfAProfileReadsBackAsTheSameCore() {
        val core = AetherCore.of(pinned)
        assertEquals(
            "aether --bind 127.0.0.1:20808 --protocol wg --scan balanced --noize balanced --ip v4 --peer 188.114.96.77:443 --quick-reconnect",
            core.command
        )
        assertEquals(core, AetherCore.ofCommand(core.command))

        val gool = AetherCore.of(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwOuter = "162.159.192.1:2408" })
        assertEquals(gool, AetherCore.ofCommand(gool.command))
        assertEquals(AetherProtocol.GOOL, AetherCore.ofCommand(gool.command)!!.protocol)
    }

    @Test
    fun aCommandIsReadAsWritten() {
        // Whatever the words mean is for the core to say; the app reads the listener and the protocol.
        val core = AetherCore.ofCommand("aether --gool --scan balanced --bind 127.0.0.1:20808 --dns 1.1.1.1")!!
        assertEquals(listOf("--gool", "--scan", "balanced", "--bind", "127.0.0.1:20808", "--dns", "1.1.1.1"), core.arguments)
        assertEquals(20808, core.port)
        assertEquals(AetherProtocol.GOOL, core.protocol)
    }

    @Test
    fun theProgramNameInFrontIsDroppedWhateverItIs() {
        val arguments = listOf("--wg", "--bind", "127.0.0.1:10819")
        assertEquals(arguments, AetherCore.ofCommand("aether --wg --bind 127.0.0.1:10819")!!.arguments)
        assertEquals(arguments, AetherCore.ofCommand("/data/app/lib/libaether.so --wg --bind 127.0.0.1:10819")!!.arguments)
        assertEquals(arguments, AetherCore.ofCommand("--wg --bind 127.0.0.1:10819")!!.arguments)
        assertEquals(arguments, AetherCore.ofCommand("  aether   --wg\t--bind 127.0.0.1:10819\n")!!.arguments)
    }

    @Test
    fun aCommandWithoutABindListensOnTheDefaultPortOfTheApp() {
        // The core's own default is another port, which no outbound of the app dials.
        val core = AetherCore.ofCommand("aether --wg --turbo")!!
        assertEquals(listOf("--wg", "--turbo", "--bind", "127.0.0.1:10819"), core.arguments)
        assertEquals(AetherCoreManager.socksPort, core.port)
    }

    @Test
    fun aCommandThatNamesNothingTheAppCanRunIsNoCore() {
        assertNull(AetherCore.ofCommand(""))
        assertNull(AetherCore.ofCommand("   "))
        assertNull(AetherCore.ofCommand("aether"))
        // A listener whose port cannot be read would otherwise be replaced without a word.
        assertNull(AetherCore.ofCommand("aether --wg --bind 10819"))
        assertNull(AetherCore.ofCommand("aether --wg --bind"))
    }

    @Test
    fun quotesKeepAWordTogether() {
        val bridge = "obfs4 1.2.3.4:443 FINGERPRINT cert=abc iat-mode=0"
        val core = AetherCore.ofCommand("aether --tor --tor-bridge \"$bridge\" --bind 127.0.0.1:10819")!!
        assertEquals(listOf("--tor", "--tor-bridge", bridge, "--bind", "127.0.0.1:10819"), core.arguments)
        assertEquals("aether --tor --tor-bridge \"$bridge\" --bind 127.0.0.1:10819", core.command)

        assertEquals(listOf("--x", "a b"), AetherCore.words("--x 'a b'"))
        assertEquals(listOf("--x", ""), AetherCore.words("--x \"\""))
        assertEquals(listOf("--x", "a b"), AetherCore.words("--x \"a b"))
        assertEquals(listOf("--x", "ab"), AetherCore.words("--x a\"\"b"))
    }

    @Test
    fun aCoreIsMovedToAnotherPortForATest() {
        val core = AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808 --scan turbo")!!

        val moved = core.on(41234)

        assertEquals(listOf("--wg", "--scan", "turbo", "--bind", "127.0.0.1:41234"), moved.arguments)
        assertEquals(41234, moved.port)
        assertNotEquals(core, moved)
        // Still the same tunnel: a process started this way runs the core, whatever it logs.
        assertTrue(core.runsAs(moved.arguments))
        assertTrue(core.runsAs(moved.arguments + listOf("--log-level", "debug")))
        assertFalse(core.runsAs(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808 --scan thorough")!!.arguments))
        assertFalse(core.runsAs(emptyList()))
    }

    @Test
    fun theSameArgumentsAreTheSameCore() {
        assertEquals(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"), AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"))
        assertNotEquals(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"), AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20809"))
        assertNotEquals(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"), AetherCore.ofCommand("aether --bind 127.0.0.1:20808 --wg"))
    }
}
