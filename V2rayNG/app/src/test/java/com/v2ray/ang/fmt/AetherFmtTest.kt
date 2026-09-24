package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherFmtTest {

    private fun link(config: ProfileItem) =
        EConfigType.AETHER.protocolScheme + AetherFmt.toUri(config)

    private fun profile(block: ProfileItem.() -> Unit) =
        ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "My Node"
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTransport = AetherTransport.HTTP3.type
            aetherScanMode = AetherScanMode.BALANCED.type
            aetherObfuscation = AetherObfuscation.BALANCED.type
            aetherIpVersion = AetherIpVersion.V4.type
            block()
        }

    @Test
    fun aPinnedMasqueNodeSurvivesTheRoundTrip() {
        val original = profile {
            server = "162.159.198.1"
            serverPort = "443"
            aetherTransport = AetherTransport.HTTP2.type
            aetherScanMode = AetherScanMode.VERIFIED.type
            aetherObfuscation = AetherObfuscation.AGGRESSIVE.type
            aetherIpVersion = AetherIpVersion.DUAL.type
            aetherFragment = true
        }

        val parsed = AetherFmt.parse(link(original))

        assertNotNull(parsed)
        assertEquals(EConfigType.AETHER, parsed?.configType)
        assertEquals("My Node", parsed?.remarks)
        assertEquals("162.159.198.1", parsed?.server)
        assertEquals("443", parsed?.serverPort)
        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("h2", parsed?.aetherTransport)
        assertEquals("verified", parsed?.aetherScanMode)
        assertEquals("aggressive", parsed?.aetherObfuscation)
        assertEquals("both", parsed?.aetherIpVersion)
        assertEquals(true, parsed?.aetherFragment)
    }

    @Test
    fun theOldNameOfTheVerifiedScanModeStillReads() {
        assertEquals(AetherScanMode.VERIFIED, AetherScanMode.fromString("stealth"))
        assertEquals(AetherScanMode.VERIFIED, AetherScanMode.fromString("verified"))
        assertEquals(AetherScanMode.BALANCED, AetherScanMode.fromString("quiet"))
        // A link written before the rename.
        val parsed = AetherFmt.parse(link(profile {}).replace("scan=balanced", "scan=stealth"))
        assertEquals("verified", parsed?.aetherScanMode)
    }

    @Test
    fun psiphonSurvivesTheRoundTripAndStaysOutOfALinkWithoutIt() {
        val chained = profile {
            aetherPsiphon = "chain"
            aetherPsiphonMode = "cdn"
            aetherPsiphonCdnIps = "1.1.1.1,1.0.0.1"
            aetherPsiphonCdnSni = "a.example,b.example"
            aetherPsiphonRegion = "DE"
        }
        val parsed = AetherFmt.parse(link(chained))
        assertEquals("chain", parsed?.aetherPsiphon)
        assertEquals("cdn", parsed?.aetherPsiphonMode)
        assertEquals("1.1.1.1,1.0.0.1", parsed?.aetherPsiphonCdnIps)
        assertEquals("a.example,b.example", parsed?.aetherPsiphonCdnSni)
        assertEquals("DE", parsed?.aetherPsiphonRegion)

        val plain = AetherFmt.toUri(profile {})
        assertFalse(plain.contains("psiphon"))
        val parsedPlain = AetherFmt.parse(link(profile {}))
        assertNull(parsedPlain?.aetherPsiphon)
        assertNull(parsedPlain?.aetherPsiphonMode)
    }

    @Test
    fun psiphonSettingsAreNormalizedAndClearedWhenPsiphonIsOff() {
        val chained = profile {
            aetherPsiphon = "chain"
            aetherPsiphonMode = "made-up"
            aetherPsiphonCdnIps = " 1.1.1.1, 1.0.0.1  8.8.8.8 "
            aetherPsiphonCdnSni = ""
            aetherPsiphonRegion = " de "
        }
        assertNull(AetherFmt.normalize(chained))
        assertEquals("auto", chained.aetherPsiphonMode)
        assertEquals("1.1.1.1,1.0.0.1,8.8.8.8", chained.aetherPsiphonCdnIps)
        assertNull(chained.aetherPsiphonCdnSni)
        assertEquals("DE", chained.aetherPsiphonRegion)

        val off = profile { aetherPsiphon = "off"; aetherPsiphonMode = "cdn"; aetherPsiphonRegion = "DE" }
        assertNull(AetherFmt.normalize(off))
        assertNull(off.aetherPsiphon)
        assertNull(off.aetherPsiphonMode)
        assertNull(off.aetherPsiphonRegion)
    }

    @Test
    fun psiphonAroundTheTunnelNeedsMasqueAndPsiphonInsideItNeedsThePortAfterTheListenPort() {
        assertEquals(
            AetherFmt.Problem.PSIPHON_NEEDS_MASQUE,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherPsiphon = "reverse" })
        )
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.MASQUE.type; aetherPsiphon = "reverse" }))
        // WireGuard inside Psiphon is fine: Psiphon carries the tunnel only the other way round.
        assertNull(AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.WIREGUARD.type; aetherPsiphon = "chain" }))

        assertEquals(AetherFmt.Problem.NEXT_PORT_TAKEN, AetherFmt.normalize(profile { aetherPsiphon = "chain" }, takenPorts = setOf(10820)))
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherPsiphon = "chain" }, takenPorts = setOf(10819)))
        assertNull(AetherFmt.normalize(profile { aetherPsiphon = "chain" }, takenPorts = setOf(10821)))
        assertEquals(AetherFmt.Problem.INVALID_LISTEN_PORT, AetherFmt.normalize(profile { aetherPsiphon = "chain"; aetherListenPort = "65535" }))
        // Without Psiphon inside, the port after the listen port is nobody's business.
        assertNull(AetherFmt.normalize(profile {}, takenPorts = setOf(10820)))
    }

    @Test
    fun aCommandWrittenInPlaceOfTheSettingsIsCheckedAndTrimmed() {
        val trimmed = profile { aetherCommand = "  aether --wg --bind 127.0.0.1:20808  " }
        assertNull(AetherFmt.normalize(trimmed))
        assertEquals("aether --wg --bind 127.0.0.1:20808", trimmed.aetherCommand)

        val blank = profile { aetherCommand = "   " }
        assertNull(AetherFmt.normalize(blank))
        assertNull(blank.aetherCommand)

        assertEquals(AetherFmt.Problem.INVALID_COMMAND, AetherFmt.normalize(profile { aetherCommand = "aether" }))
        assertEquals(AetherFmt.Problem.INVALID_COMMAND, AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 20808" }))
        // The ports a command names are held to the same rule as a listen port.
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherCommand = "aether --wg --bind 127.0.0.1:10808" }, takenPorts = setOf(10808))
        )
        assertEquals(
            AetherFmt.Problem.LISTEN_PORT_TAKEN,
            AetherFmt.normalize(profile { aetherCommand = "aether --psiphon --bind 127.0.0.1:10808 --psiphon-bind 127.0.0.1:10819" }, takenPorts = setOf(10808))
        )
    }

    @Test
    fun fragmentValuesSurviveTheRoundTrip() {
        val uri = link(profile {
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
            aetherFragmentSize = "16-32"
            aetherFragmentDelay = "5"
        })

        val parsed = AetherFmt.parse(uri)
        assertEquals("16-32", parsed?.aetherFragmentSize)
        assertEquals("5", parsed?.aetherFragmentDelay)

        val broken = AetherFmt.parse("aether://?protocol=masque&transport=h2&fragment=1&fragment_size=0&fragment_delay=x#X")
        assertNull(broken?.aetherFragmentSize)
        assertNull(broken?.aetherFragmentDelay)
    }

    @Test
    fun fragmentValuesAreCheckedOnlyWhenTheyAreUsed() {
        val used = profile {
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
            aetherFragmentSize = "32 - 16"
            aetherFragmentDelay = ""
        }
        assertNull(AetherFmt.normalize(used))
        assertEquals("16-32", used.aetherFragmentSize)
        assertNull(used.aetherFragmentDelay)

        assertEquals(
            AetherFmt.Problem.INVALID_FRAGMENT,
            AetherFmt.normalize(used.copy(aetherFragmentDelay = "5000"))
        )

        val unused = used.copy(aetherTransport = AetherTransport.HTTP3.type, aetherFragmentDelay = "5000")
        assertNull(AetherFmt.normalize(unused))
        assertNull(unused.aetherFragmentDelay)
    }

    @Test
    fun bothGoolHopsSurviveTheRoundTrip() {
        val original = profile {
            remarks = "Gool"
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "[2606:4700:d0::a29f:c001]:894"
        }

        val parsed = AetherFmt.parse(link(original))

        assertEquals("gool", parsed?.aetherProtocol)
        assertEquals("162.159.192.1:2408", parsed?.aetherWiwOuter)
        assertEquals("[2606:4700:d0::a29f:c001]:894", parsed?.aetherWiwInner)
        assertNull(parsed?.server)
        assertNull(parsed?.serverPort)
    }

    @Test
    fun aNodeLeftToTheScannerCarriesNoEndpoint() {
        val uri = link(profile {})

        assertTrue("uri should hold only a query: $uri", uri.startsWith("aether://?"))

        val parsed = AetherFmt.parse(uri)
        assertNull(parsed?.server)
        assertNull(parsed?.serverPort)
        assertEquals("masque", parsed?.aetherProtocol)
    }

    @Test
    fun anIpv6EndpointSurvivesTheRoundTrip() {
        val uri = link(profile {
            server = "2606:4700:d0::a29f:c001"
            serverPort = "443"
        })
        assertTrue("uri should bracket the address: $uri", uri.startsWith("aether://[2606:4700:d0::a29f:c001]:443?"))

        val parsed = AetherFmt.parse(uri)
        assertEquals("2606:4700:d0::a29f:c001", parsed?.server)
        assertEquals("443", parsed?.serverPort)
    }

    @Test
    fun theHopsOnlyRideAlongWithGool() {
        val uri = link(profile {
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "188.114.96.1:894"
        })

        assertFalse(uri.contains("outer="))
        assertFalse(uri.contains("inner="))
    }

    @Test
    fun theEndpointNeverRidesAlongWithGool() {
        val uri = link(profile {
            aetherProtocol = AetherProtocol.GOOL.type
            server = "162.159.198.1"
            serverPort = "443"
        })

        assertTrue("uri should hold only a query: $uri", uri.startsWith("aether://?"))
        assertNull(AetherFmt.parse("aether://162.159.198.1:443?protocol=gool#X")?.server)
    }

    @Test
    fun theTransportOnlyRidesAlongWithMasque() {
        val uri = link(profile {
            aetherProtocol = AetherProtocol.WIREGUARD.type
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
        })

        assertFalse(uri.contains("transport="))
        assertFalse(uri.contains("fragment="))
        assertEquals("wg", AetherFmt.parse(uri)?.aetherProtocol)
    }

    @Test
    fun aLinkWithoutSettingsFallsBackToTheDefaults() {
        val parsed = AetherFmt.parse("aether://#Shared")

        assertEquals("Shared", parsed?.remarks)
        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("h3", parsed?.aetherTransport)
        assertEquals("balanced", parsed?.aetherScanMode)
        assertEquals("balanced", parsed?.aetherObfuscation)
        assertEquals("v4", parsed?.aetherIpVersion)
        assertEquals(false, parsed?.aetherFragment)
    }

    @Test
    fun aNamelessLinkStillGetsALabel() {
        assertEquals("Aether", AetherFmt.parse("aether://?protocol=wg")?.remarks)
    }

    @Test
    fun anUnknownSettingFallsBackInsteadOfFailing() {
        val parsed = AetherFmt.parse("aether://?protocol=quantum&scan=instant&ip=v9#X")

        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("balanced", parsed?.aetherScanMode)
        assertEquals("v4", parsed?.aetherIpVersion)
    }

    @Test
    fun anEndpointTheCoreCannotUseIsDroppedFromALink() {
        val hostname = AetherFmt.parse("aether://engage.cloudflareclient.com:2408?protocol=wg#X")
        assertNull(hostname?.server)
        assertNull(hostname?.serverPort)

        val portless = AetherFmt.parse("aether://162.159.198.1?protocol=masque#X")
        assertNull(portless?.server)
        assertNull(portless?.serverPort)
    }

    @Test
    fun goolHopsFromALinkAreCheckedAndNormalized() {
        val parsed = AetherFmt.parse(
            "aether://?protocol=gool&outer=162.159.192.1%3A2408&inner=%5B2606%3A4700%3A0%3A0%3A0%3A0%3A0%3A1%5D%3A894#X"
        )
        assertEquals("162.159.192.1:2408", parsed?.aetherWiwOuter)
        assertEquals("[2606:4700::1]:894", parsed?.aetherWiwInner)

        val malformed = AetherFmt.parse("aether://?protocol=gool&outer=162.159.192.1&inner=example.com%3A894#X")
        assertNull(malformed?.aetherWiwOuter)
        assertNull(malformed?.aetherWiwInner)

        val shared = AetherFmt.parse("aether://?protocol=gool&outer=162.159.192.1%3A2408&inner=162.159.192.1%3A894#X")
        assertEquals("162.159.192.1:2408", shared?.aetherWiwOuter)
        assertNull(shared?.aetherWiwInner)
    }

    @Test
    fun anEmptyEndpointMeansScanning() {
        val config = profile {
            server = "  "
            serverPort = "443"
            aetherWiwOuter = "162.159.192.1:2408"
        }

        assertNull(AetherFmt.normalize(config))
        assertNull(config.server)
        assertNull(config.serverPort)
        assertNull(config.aetherWiwOuter)
    }

    @Test
    fun aPinnedEndpointIsNormalizedBeforeItIsSaved() {
        val config = profile {
            server = " [2606:4700:0:0:0:0:0:1] "
            serverPort = " 0443 "
        }

        assertNull(AetherFmt.normalize(config))
        assertEquals("2606:4700::1", config.server)
        assertEquals("443", config.serverPort)
    }

    @Test
    fun aPinnedEndpointTheCoreCannotUseIsRejected() {
        assertEquals(
            AetherFmt.Problem.INVALID_PEER,
            AetherFmt.normalize(profile { server = "engage.cloudflareclient.com"; serverPort = "2408" })
        )
        assertEquals(
            AetherFmt.Problem.INVALID_PEER,
            AetherFmt.normalize(profile { server = "162.159.198.1"; serverPort = "" })
        )
        assertEquals(
            AetherFmt.Problem.INVALID_PEER,
            AetherFmt.normalize(profile { server = "162.159.198.1"; serverPort = "70000" })
        )
    }

    @Test
    fun goolHopsAreNormalizedAndTheEndpointIsCleared() {
        val config = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            server = "162.159.198.1"
            serverPort = "443"
            aetherWiwOuter = " 162.159.192.1:2408 "
            aetherWiwInner = ""
        }

        assertNull(AetherFmt.normalize(config))
        assertEquals("162.159.192.1:2408", config.aetherWiwOuter)
        assertNull(config.aetherWiwInner)
        assertNull(config.server)
        assertNull(config.serverPort)
    }

    @Test
    fun aGoolHopTheCoreCannotUseIsRejected() {
        assertEquals(
            AetherFmt.Problem.INVALID_HOP,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwOuter = "162.159.192.1" })
        )
        assertEquals(
            AetherFmt.Problem.INVALID_HOP,
            AetherFmt.normalize(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwInner = "2606:4700::1:894" })
        )
    }

    @Test
    fun goolHopsMustLeaveThroughDifferentAddresses() {
        val config = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "162.159.192.1:894"
        }

        assertEquals(AetherFmt.Problem.SHARED_HOP, AetherFmt.normalize(config))
        assertEquals("162.159.192.1:2408", config.aetherWiwOuter)
        assertEquals("162.159.192.1:894", config.aetherWiwInner)
    }

    @Test
    fun aChosenListenPortSurvivesTheRoundTripAndTheDefaultStaysOutOfTheLink() {
        val chosen = profile { aetherListenPort = "20808" }
        assertTrue(link(chosen).contains("listen=20808"))
        assertEquals("20808", AetherFmt.parse(link(chosen))?.aetherListenPort)

        assertFalse(link(profile { }).contains("listen="))
        assertFalse(link(profile { aetherListenPort = "10819" }).contains("listen="))
        assertNull(AetherFmt.parse(link(profile { }))?.aetherListenPort)
        // A link falls back to the default for a port that is none, as it does for its other settings.
        assertNull(AetherFmt.parse(link(profile { }).replace("?", "?listen=70000&"))?.aetherListenPort)
        assertNull(AetherFmt.parse(link(profile { }).replace("?", "?listen=10819&"))?.aetherListenPort)
    }

    @Test
    fun theListenPortIsCheckedAndStoredOnlyWhenItIsNotTheDefault() {
        val chosen = profile { aetherListenPort = " 020808 " }
        assertNull(AetherFmt.normalize(chosen))
        assertEquals("20808", chosen.aetherListenPort)

        val default = profile { aetherListenPort = "10819" }
        assertNull(AetherFmt.normalize(default))
        assertNull(default.aetherListenPort)

        val blank = profile { aetherListenPort = "  " }
        assertNull(AetherFmt.normalize(blank))
        assertNull(blank.aetherListenPort)

        for (invalid in listOf("0", "65536", "-1", "socks", "10819.5")) {
            assertEquals(AetherFmt.Problem.INVALID_LISTEN_PORT, AetherFmt.normalize(profile { aetherListenPort = invalid }))
        }
    }

    @Test
    fun theListenPortCannotBeAPortTheLocalProxyListensOn() {
        val localProxy = setOf(10808, 10809)

        val onSocks = profile { aetherListenPort = "10808" }
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(onSocks, localProxy))
        // A refused profile keeps what was typed, for the editor to show again.
        assertEquals("10808", onSocks.aetherListenPort)
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherListenPort = " 10809 " }, localProxy))

        val free = profile { aetherListenPort = "20808" }
        assertNull(AetherFmt.normalize(free, localProxy))
        assertEquals("20808", free.aetherListenPort)
        assertNull(AetherFmt.normalize(profile { }, localProxy))
    }

    @Test
    fun theDefaultListenPortIsTakenOnceTheLocalProxyWasMovedOntoIt() {
        val movedOntoIt = setOf(10819)

        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { }, movedOntoIt))
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherListenPort = "" }, movedOntoIt))
        assertEquals(AetherFmt.Problem.LISTEN_PORT_TAKEN, AetherFmt.normalize(profile { aetherListenPort = "10819" }, movedOntoIt))
        assertNull(AetherFmt.normalize(profile { aetherListenPort = "20808" }, movedOntoIt))
    }

    @Test
    fun withoutKnownLocalPortsOnlyTheListenPortItselfIsChecked() {
        // The local proxy port is picked at random on every start, or the caller has none to name.
        assertNull(AetherFmt.normalize(profile { aetherListenPort = "10808" }))
        assertNull(AetherFmt.normalize(profile { aetherListenPort = "10808" }, emptySet()))
        // What is no port at all is reported as that, whatever is taken.
        assertEquals(AetherFmt.Problem.INVALID_LISTEN_PORT, AetherFmt.normalize(profile { aetherListenPort = "0" }, setOf(10808)))
    }

    @Test
    fun theListenPortIsReadFromAProfile() {
        assertEquals(20808, AetherFmt.listenPortOf("20808"))
        assertEquals(1, AetherFmt.listenPortOf(" 1 "))
        assertEquals(65535, AetherFmt.listenPortOf("65535"))
        assertNull(AetherFmt.listenPortOf(null))
        assertNull(AetherFmt.listenPortOf(""))
        assertNull(AetherFmt.listenPortOf("0"))
        assertNull(AetherFmt.listenPortOf("65536"))
        assertEquals("20808", AetherFmt.storedListenPort(20808))
        assertNull(AetherFmt.storedListenPort(10819))
    }

    /** aetherSettings, the form a custom configuration named its core in before aetherCommand; it is still read. */
    private fun read(json: String): AetherFmt.Settings = AetherFmt.fromSettings(JsonParser.parseString(json).asJsonObject)

    private fun valid(json: String): ProfileItem = (read(json) as AetherFmt.Settings.Valid).profile

    @Test
    fun aPinnedMasqueNodeIsReadFromItsSettings() {
        val original = profile {
            server = "162.159.198.1"
            serverPort = "443"
            aetherTransport = AetherTransport.HTTP2.type
            aetherScanMode = AetherScanMode.VERIFIED.type
            aetherObfuscation = AetherObfuscation.AGGRESSIVE.type
            aetherIpVersion = AetherIpVersion.DUAL.type
            aetherFragment = true
            aetherFragmentSize = "16-32"
            aetherFragmentDelay = "5"
        }

        // As a full configuration wrote them into its SOCKS outbound before aetherCommand; the scan mode
        // still carries the name it had then.
        val parsed = valid(
            """{"address":"162.159.198.1","port":"443","protocol":"masque","transport":"h2","scan":"stealth",""" +
                """"noize":"aggressive","ip":"both","fragment":true,"fragmentSize":"16-32","fragmentDelay":"5"}"""
        )
        assertEquals(EConfigType.AETHER, parsed.configType)
        assertEquals(original.duplicateIdentity(), parsed.duplicateIdentity())
    }

    @Test
    fun bothGoolHopsAreReadFromTheirSettings() {
        val original = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "[2606:4700:d0::a29f:c001]:894"
            aetherFragment = false
        }

        val parsed = valid(
            """{"protocol":"gool","scan":"balanced","noize":"balanced","ip":"v4",""" +
                """"outer":"162.159.192.1:2408","inner":"[2606:4700:d0::a29f:c001]:894"}"""
        )
        assertEquals(original.duplicateIdentity(), parsed.duplicateIdentity())
    }

    @Test
    fun emptySettingsAreTheDefaultsAndAScannedEndpoint() {
        val parsed = valid("{}")
        assertEquals(AetherProtocol.MASQUE.type, parsed.aetherProtocol)
        assertEquals(AetherTransport.HTTP3.type, parsed.aetherTransport)
        assertEquals(AetherScanMode.BALANCED.type, parsed.aetherScanMode)
        assertEquals(AetherObfuscation.BALANCED.type, parsed.aetherObfuscation)
        assertEquals(AetherIpVersion.V4.type, parsed.aetherIpVersion)
        assertEquals(false, parsed.aetherFragment)
        assertNull(parsed.server)
        assertNull(parsed.serverPort)

        // Null and empty values are left-out values.
        val blank = valid("""{"address": "", "port": null, "protocol": null, "scan": " "}""")
        assertNull(blank.server)
        assertEquals(AetherProtocol.MASQUE.type, blank.aetherProtocol)
        assertEquals(AetherScanMode.BALANCED.type, blank.aetherScanMode)
    }

    @Test
    fun handWrittenSettingsMayUseNumbersAndCapitals() {
        val parsed = valid(
            """{"address": "188.114.96.77", "port": 443, "protocol": "WG", "scan": "Balanced", "noize": "aggressive", "ip": "both"}"""
        )
        assertEquals("188.114.96.77", parsed.server)
        assertEquals("443", parsed.serverPort)
        assertEquals(AetherProtocol.WIREGUARD.type, parsed.aetherProtocol)
        assertEquals(AetherScanMode.BALANCED.type, parsed.aetherScanMode)
        assertEquals(AetherObfuscation.AGGRESSIVE.type, parsed.aetherObfuscation)
        assertEquals(AetherIpVersion.DUAL.type, parsed.aetherIpVersion)

        val fragmented = valid("""{"transport": "h2", "fragment": true, "fragmentSize": 16, "fragmentDelay": "2-10", "port": "443"}""")
        assertEquals(true, fragmented.aetherFragment)
        assertEquals("16", fragmented.aetherFragmentSize)
        assertEquals("2-10", fragmented.aetherFragmentDelay)
        // A port without an address pins nothing.
        assertNull(fragmented.serverPort)
    }

    @Test
    fun anUnknownKeyOrModeIsReportedInsteadOfDefaulted() {
        assertEquals(AetherFmt.Settings.Unknown("noise"), read("""{"noise": "off"}"""))
        assertEquals(AetherFmt.Settings.Unknown("protocol: wireguard"), read("""{"protocol": "wireguard"}"""))
        assertEquals(AetherFmt.Settings.Unknown("transport: quic"), read("""{"transport": "quic"}"""))
        assertEquals(AetherFmt.Settings.Unknown("scan: fast"), read("""{"scan": "fast"}"""))
        assertEquals(AetherFmt.Settings.Unknown("noize: gfw"), read("""{"noize": "gfw"}"""))
        assertEquals(AetherFmt.Settings.Unknown("ip: 4"), read("""{"ip": 4}"""))
        assertEquals(AetherFmt.Settings.Unknown("fragment: yes"), read("""{"fragment": "yes"}"""))
        assertEquals(AetherFmt.Settings.Unknown("scan"), read("""{"scan": {"mode": "turbo"}}"""))
        assertEquals(AetherFmt.Settings.Unknown("address"), read("""{"address": ["188.114.96.77"]}"""))
    }

    @Test
    fun settingsTheEditorRefusesAreRefusedHereAsWell() {
        assertEquals(AetherFmt.Settings.Refused(AetherFmt.Problem.INVALID_PEER), read("""{"address": "engage.cloudflareclient.com", "port": 2408}"""))
        assertEquals(AetherFmt.Settings.Refused(AetherFmt.Problem.INVALID_PEER), read("""{"address": "188.114.96.77"}"""))
        assertEquals(AetherFmt.Settings.Refused(AetherFmt.Problem.INVALID_PEER), read("""{"address": "188.114.96.77", "port": 70000}"""))
        assertEquals(AetherFmt.Settings.Refused(AetherFmt.Problem.INVALID_HOP), read("""{"protocol": "gool", "outer": "162.159.192.1"}"""))
        assertEquals(
            AetherFmt.Settings.Refused(AetherFmt.Problem.SHARED_HOP),
            read("""{"protocol": "gool", "outer": "162.159.192.1:2408", "inner": "162.159.192.1:894"}""")
        )
        assertEquals(
            AetherFmt.Settings.Refused(AetherFmt.Problem.INVALID_FRAGMENT),
            read("""{"transport": "h2", "fragment": true, "fragmentSize": "0"}""")
        )
    }
}
