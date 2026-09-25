package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.AetherRange
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherPsiphonCdnSet
import com.v2ray.ang.enums.AetherPsiphonMode
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.AetherTorBridges
import com.v2ray.ang.enums.AetherTorRelays
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI
import java.util.Locale

object AetherFmt : FmtBase() {

    enum class Problem {
        INVALID_PEER,
        INVALID_HOP,
        SHARED_HOP,
        INVALID_FRAGMENT,
        INVALID_DNS,
        INVALID_EXIT_LOC,
        INVALID_LISTEN_PORT,
        LISTEN_PORT_TAKEN,
        PSIPHON_NEEDS_MASQUE,
        NEXT_PORT_TAKEN,
        TOR_NEEDS_MASQUE,
        TOR_PSIPHON_CONFLICT,
        TOR_BRIDGES_MISSING,
        INVALID_COMMAND,
    }

    /** Every key of aetherSettings, the form a custom configuration named its core in before aetherCommand. */
    private val settingsKeys = setOf(
        "address", "port", "protocol", "transport", "scan", "noize", "ip",
        "fragment", "fragmentSize", "fragmentDelay", "outer", "inner",
    )

    /** The keys of aetherSettings whose value is one of a fixed set of modes. */
    private val settingsModes = mapOf(
        "protocol" to AetherProtocol.entries.map { it.type },
        "transport" to AetherTransport.entries.map { it.type },
        "scan" to AetherScanMode.entries.map { it.type } + AetherScanMode.STEALTH,
        "noize" to AetherObfuscation.entries.map { it.type },
        "ip" to AetherIpVersion.entries.map { it.type },
        "fragment" to listOf("true", "false"),
    )

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.AETHER)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)
        val protocol = AetherProtocol.fromString(queryParam["protocol"])

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "Aether" }
        config.aetherProtocol = protocol.type
        config.aetherTransport = AetherTransport.fromString(queryParam["transport"]).type
        config.aetherScanMode = AetherScanMode.fromString(queryParam["scan"]).type
        config.aetherObfuscation = AetherObfuscation.fromString(queryParam["noize"]).type
        config.aetherIpVersion = AetherIpVersion.fromString(queryParam["ip"]).type
        config.aetherFragment = queryParam["fragment"] == "1"
        config.aetherFragmentSize = AetherRange.parse(queryParam["fragment_size"], AetherRange.FRAGMENT_SIZE)?.toString()
        config.aetherFragmentDelay = AetherRange.parse(queryParam["fragment_delay"], AetherRange.FRAGMENT_DELAY)?.toString()
        config.aetherEch = queryParam["ech"] == "1"
        config.aetherDns = queryParam["dns"]
        config.aetherExitLoc = queryParam["exit_loc"]
        config.aetherListenPort = listenPortOf(queryParam["listen"])?.let(::storedListenPort)
        config.aetherPsiphon = AetherPsiphon.fromString(queryParam["psiphon"]).type.takeUnless { it == AetherPsiphon.OFF.type }
        config.aetherPsiphonMode = queryParam["psiphon_mode"]?.let { AetherPsiphonMode.fromString(it).type }
        config.aetherPsiphonCdnIps = queryParam["cdn_ips"]
        config.aetherPsiphonCdnSni = queryParam["cdn_sni"]
        config.aetherPsiphonCdnSets = queryParam["cdn_sets"]
        config.aetherPsiphonRegion = queryParam["region"]
        config.aetherPsiphonBundledList = if (queryParam["psiphon_bundled"] == "0") false else null
        config.aetherTor = AetherTor.fromString(queryParam["tor"]).type.takeUnless { it == AetherTor.OFF.type }
        config.aetherTorBridges = queryParam["tor_bridges"]?.let { AetherTorBridges.fromString(it).type }
        config.aetherTorBridgeLines = queryParam["bridges"]?.split(';')?.joinToString("\n")
        config.aetherTorRelays = queryParam["tor_relays"]?.let { AetherTorRelays.fromString(it).type }

        if (protocol.twoHops) {
            val outer = AetherEndpoint.parse(queryParam["outer"])
            val inner = AetherEndpoint.parse(queryParam["inner"])?.takeUnless { it.host == outer?.host }
            config.aetherWiwOuter = outer?.toString()
            config.aetherWiwInner = inner?.toString()
        } else {
            val endpoint = AetherEndpoint.of(uri.idnHost, uri.port.takeIf { it > 0 }?.toString())
            config.server = endpoint?.host
            config.serverPort = endpoint?.port?.toString()
        }

        return config
    }

    fun toUri(config: ProfileItem): String {
        val protocol = AetherProtocol.fromString(config.aetherProtocol)
        val query = linkedMapOf(
            "protocol" to protocol.type,
            "scan" to AetherScanMode.fromString(config.aetherScanMode).type,
        )
        // Automatic obfuscation is the core's own choice per protocol; a link says nothing about it.
        AetherObfuscation.fromString(config.aetherObfuscation).takeUnless { it == AetherObfuscation.AUTO }?.let { query["noize"] = it.type }
        query["ip"] = AetherIpVersion.fromString(config.aetherIpVersion).type
        config.aetherDns?.takeIf { it.isNotBlank() }?.let { query["dns"] = it }
        config.aetherExitLoc?.takeIf { it.isNotBlank() }?.let { query["exit_loc"] = it }
        if (protocol.overMasque) {
            query["transport"] = AetherTransport.fromString(config.aetherTransport).type
            if (config.aetherFragment == true) {
                query["fragment"] = "1"
                AetherRange.parse(config.aetherFragmentSize, AetherRange.FRAGMENT_SIZE)
                    ?.let { query["fragment_size"] = it.toString() }
                AetherRange.parse(config.aetherFragmentDelay, AetherRange.FRAGMENT_DELAY)
                    ?.let { query["fragment_delay"] = it.toString() }
            }
            if (config.aetherEch == true) query["ech"] = "1"
        }
        if (protocol.twoHops) {
            AetherEndpoint.parse(config.aetherWiwOuter)?.let { query["outer"] = it.toString() }
            AetherEndpoint.parse(config.aetherWiwInner)?.let { query["inner"] = it.toString() }
        }
        listenPortOf(config.aetherListenPort)?.let(::storedListenPort)?.let { query["listen"] = it }
        val psiphon = AetherPsiphon.fromString(config.aetherPsiphon)
        if (psiphon != AetherPsiphon.OFF) {
            query["psiphon"] = psiphon.type
            query["psiphon_mode"] = AetherPsiphonMode.fromString(config.aetherPsiphonMode).type
            config.aetherPsiphonCdnIps?.takeIf { it.isNotBlank() }?.let { query["cdn_ips"] = it }
            config.aetherPsiphonCdnSni?.takeIf { it.isNotBlank() }?.let { query["cdn_sni"] = it }
            config.aetherPsiphonCdnSets?.takeIf { it.isNotBlank() }?.let { query["cdn_sets"] = it }
            config.aetherPsiphonRegion?.takeIf { it.isNotBlank() }?.let { query["region"] = it }
            if (config.aetherPsiphonBundledList == false) query["psiphon_bundled"] = "0"
        }
        val tor = AetherTor.fromString(config.aetherTor)
        if (tor != AetherTor.OFF) {
            query["tor"] = tor.type
            query["tor_bridges"] = AetherTorBridges.fromString(config.aetherTorBridges).type
            query["tor_relays"] = AetherTorRelays.fromString(config.aetherTorRelays).type
            // Bridge lines never hold a semicolon: the core itself separates them with one.
            bridgeLines(config.aetherTorBridgeLines).takeIf { it.isNotEmpty() }?.let { query["bridges"] = it.joinToString(";") }
        }
        val endpoint = AetherEndpoint.of(config.server, config.serverPort).takeUnless { protocol.twoHops }

        val queryText = query.entries.joinToString("&") { "${it.key}=${Utils.encodeURIComponent(it.value)}" }
        return "${endpoint ?: ""}?$queryText#${Utils.encodeURIComponent(config.remarks)}"
    }

    /** aetherSettings read into the profile their core is started with, or what stops that. */
    sealed interface Settings {

        data class Valid(val profile: ProfileItem) : Settings

        sealed interface Invalid : Settings

        /** A value the profile editor refuses as well. */
        data class Refused(val problem: Problem) : Invalid

        /** A key there is no setting for, or a value its setting has no such mode for; [entry] names it. */
        data class Unknown(val entry: String) : Invalid
    }

    /**
     * Reads aetherSettings, the form a custom configuration named its core in before aetherCommand;
     * one written that way still runs. A share link falls back to the default for a mode it does
     * not know; here that would start a tunnel other than the one written down, so an unknown key
     * or mode is reported instead. A value may be a string, a number or a boolean; a missing, null
     * or empty one is the default, and the endpoint left out is scanned for.
     */
    fun fromSettings(settings: JsonObject): Settings {
        val values = mutableMapOf<String, String>()
        for ((key, value) in settings.entrySet()) {
            if (key !in settingsKeys || !(value.isJsonNull || value.isJsonPrimitive)) return Settings.Unknown(key)
            val text = if (value.isJsonNull) "" else value.asString.trim()
            if (text.isNotEmpty()) values[key] = text
        }
        for ((key, modes) in settingsModes) {
            val mode = values[key]?.lowercase(Locale.ROOT) ?: continue
            if (mode !in modes) return Settings.Unknown("$key: ${values[key]}")
            values[key] = mode
        }

        val config = ProfileItem.create(EConfigType.AETHER)
        // aetherSettings, the released format before aetherCommand, meant MASQUE when it named no
        // protocol, as the core itself does; that stays, whatever a new profile starts on.
        config.aetherProtocol = (AetherProtocol.entries.find { it.type == values["protocol"] } ?: AetherProtocol.MASQUE).type
        config.aetherTransport = AetherTransport.fromString(values["transport"]).type
        config.aetherScanMode = AetherScanMode.fromString(values["scan"]).type
        config.aetherObfuscation = AetherObfuscation.fromString(values["noize"]).type
        config.aetherIpVersion = AetherIpVersion.fromString(values["ip"]).type
        config.aetherFragment = values["fragment"] == "true"
        config.aetherFragmentSize = values["fragmentSize"]
        config.aetherFragmentDelay = values["fragmentDelay"]
        config.aetherWiwOuter = values["outer"]
        config.aetherWiwInner = values["inner"]
        config.server = values["address"]
        config.serverPort = values["port"]
        return normalize(config)?.let(Settings::Refused) ?: Settings.Valid(config)
    }

    /** The loopback port [text] names for the core to listen on, null when it names none. */
    fun listenPortOf(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /**
     * The listen port as a profile stores it: nothing for the default, so a profile saved before
     * the port could be chosen and one saved with the default stay the same profile.
     */
    fun storedListenPort(port: Int): String? = port.toString().takeUnless { it == AppConfig.PORT_AETHER_SOCKS }

    /**
     * [takenPorts] are loopback ports something else of the app listens on, the local proxy above
     * all; the core of the profile cannot listen there as well.
     */
    fun normalize(config: ProfileItem, takenPorts: Set<Int> = emptySet()): Problem? =
        normalizeFragment(config)
            ?: normalizeEndpoints(config)
            ?: normalizeDns(config)
            ?: normalizeExitLoc(config)
            ?: normalizePsiphon(config)
            ?: normalizeTor(config)
            ?: normalizeListenPort(config, takenPorts)
            ?: normalizeCommand(config, takenPorts)

    private fun normalizeListenPort(config: ProfileItem, takenPorts: Set<Int>): Problem? {
        val text = config.aetherListenPort?.trim().orEmpty()
        val port = listenPortOf(text)
        if (text.isNotEmpty() && port == null) return Problem.INVALID_LISTEN_PORT
        // The default port can be taken too, once the local proxy has been moved onto it.
        val listen = port ?: AppConfig.PORT_AETHER_SOCKS.toInt()
        if (listen in takenPorts) return Problem.LISTEN_PORT_TAKEN
        // Psiphon inside the tunnel, Tor inside it and Tor around it each take one more port after the
        // one the app dials, as AetherCoreManager.buildArguments hands them out.
        val tor = AetherTor.fromString(config.aetherTor)
        val more = listOf(
            AetherPsiphon.fromString(config.aetherPsiphon) == AetherPsiphon.CHAIN,
            tor == AetherTor.CHAIN,
            tor == AetherTor.REVERSE,
        ).count { it }
        if (listen + more > 65535) return Problem.INVALID_LISTEN_PORT
        if ((1..more).any { listen + it in takenPorts }) return Problem.NEXT_PORT_TAKEN
        config.aetherListenPort = port?.let(::storedListenPort)
        return null
    }

    /** The resolvers, each an address with or without a port, as the core reads them; written back comma-separated. */
    private fun normalizeDns(config: ProfileItem): Problem? {
        val resolvers = config.aetherDns.orEmpty().split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
        if (resolvers.any { AetherEndpoint.parse(it) == null && AetherEndpoint.of(it, "53") == null }) return Problem.INVALID_DNS
        config.aetherDns = resolvers.joinToString(",").ifEmpty { null }
        return null
    }

    /** The exit rule as the core reads it: country codes to allow, or with a leading ! to refuse, kept in capitals. */
    private fun normalizeExitLoc(config: ProfileItem): Problem? {
        val rule = config.aetherExitLoc.orEmpty().filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)
        if (rule.isEmpty()) {
            config.aetherExitLoc = null
            return null
        }
        if (!exitRule.matches(rule)) return Problem.INVALID_EXIT_LOC
        config.aetherExitLoc = rule
        return null
    }

    private val exitRule = Regex("!?[A-Z]{2}(,[A-Z]{2})*")

    private fun normalizePsiphon(config: ProfileItem): Problem? {
        val psiphon = AetherPsiphon.fromString(config.aetherPsiphon)
        if (psiphon == AetherPsiphon.OFF) {
            config.aetherPsiphon = null
            config.aetherPsiphonMode = null
            config.aetherPsiphonCdnIps = null
            config.aetherPsiphonCdnSni = null
            config.aetherPsiphonCdnSets = null
            config.aetherPsiphonRegion = null
            config.aetherPsiphonBundledList = null
            return null
        }
        // Psiphon carries TCP alone and WARP's WireGuard endpoints answer on UDP; the core refuses the pair.
        if (psiphon == AetherPsiphon.REVERSE && !AetherProtocol.fromString(config.aetherProtocol).overMasque) {
            return Problem.PSIPHON_NEEDS_MASQUE
        }
        config.aetherPsiphon = psiphon.type
        config.aetherPsiphonMode = AetherPsiphonMode.fromString(config.aetherPsiphonMode).type
        config.aetherPsiphonCdnIps = commaList(config.aetherPsiphonCdnIps)
        config.aetherPsiphonCdnSni = commaList(config.aetherPsiphonCdnSni)
        config.aetherPsiphonCdnSets = AetherPsiphonCdnSet.join(AetherPsiphonCdnSet.parse(config.aetherPsiphonCdnSets))
        config.aetherPsiphonRegion = config.aetherPsiphonRegion?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null }
        // Stored only when it says no; yes is the default and needs no word.
        config.aetherPsiphonBundledList = config.aetherPsiphonBundledList?.takeUnless { it }
        return null
    }

    private fun normalizeTor(config: ProfileItem): Problem? {
        val tor = AetherTor.fromString(config.aetherTor)
        if (tor == AetherTor.OFF) {
            config.aetherTor = null
            config.aetherTorBridges = null
            config.aetherTorBridgeLines = null
            config.aetherTorRelays = null
            return null
        }
        // Tor carries TCP alone and WARP's WireGuard endpoints answer on UDP; the core refuses the pair.
        if (tor == AetherTor.REVERSE && !AetherProtocol.fromString(config.aetherProtocol).overMasque) {
            return Problem.TOR_NEEDS_MASQUE
        }
        // Tor and Psiphon go together only nested, one inside the tunnel and the other around it: two around
        // it the core refuses, two inside it or one alone leaves the app nothing to dial the other on.
        val psiphon = AetherPsiphon.fromString(config.aetherPsiphon)
        val nested = psiphon == AetherPsiphon.OFF ||
            (tor == AetherTor.CHAIN && psiphon == AetherPsiphon.REVERSE) ||
            (tor == AetherTor.REVERSE && psiphon == AetherPsiphon.CHAIN)
        if (!nested) return Problem.TOR_PSIPHON_CONFLICT
        val bridges = AetherTorBridges.fromString(config.aetherTorBridges)
        val lines = bridgeLines(config.aetherTorBridgeLines)
        if (bridges == AetherTorBridges.OWN && lines.isEmpty()) return Problem.TOR_BRIDGES_MISSING
        config.aetherTor = tor.type
        config.aetherTorBridges = bridges.type
        config.aetherTorBridgeLines = lines.takeIf { bridges == AetherTorBridges.OWN }?.joinToString("\n")
        config.aetherTorRelays = AetherTorRelays.fromString(config.aetherTorRelays).type
        return null
    }

    /**
     * The bridge lines in [text], one per line the way torrc writes them, read as the core reads a
     * bridge file: blank lines and comments dropped, a leading Bridge keyword taken off.
     */
    fun bridgeLines(text: String?): List<String> =
        text.orEmpty().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("Bridge ").removePrefix("bridge ").trim() }
            .filter { it.isNotEmpty() }

    /** A list as the core reads it, entries separated by commas or spaces, written back with commas alone. */
    private fun commaList(text: String?): String? =
        text?.split(Regex("[,\\s]+"))?.filter { it.isNotEmpty() }?.joinToString(",")?.ifEmpty { null }

    /** A command written in place of the settings has to be one the app can run, on ports nothing else of the app holds. */
    private fun normalizeCommand(config: ProfileItem, takenPorts: Set<Int>): Problem? {
        val text = config.aetherCommand?.trim().orEmpty()
        config.aetherCommand = text.ifEmpty { null }
        if (text.isEmpty()) return null
        val core = AetherCore.ofCommand(text) ?: return Problem.INVALID_COMMAND
        return if (core.ports.any { it in takenPorts }) Problem.LISTEN_PORT_TAKEN else null
    }

    private fun normalizeFragment(config: ProfileItem): Problem? {
        val inUse = AetherProtocol.fromString(config.aetherProtocol).overMasque &&
            AetherTransport.fromString(config.aetherTransport) == AetherTransport.HTTP2 &&
            config.aetherFragment == true
        val sizeText = config.aetherFragmentSize?.trim().orEmpty()
        val delayText = config.aetherFragmentDelay?.trim().orEmpty()
        val size = AetherRange.parse(sizeText, AetherRange.FRAGMENT_SIZE)
        val delay = AetherRange.parse(delayText, AetherRange.FRAGMENT_DELAY)
        if (inUse && (sizeText.isNotEmpty() && size == null || delayText.isNotEmpty() && delay == null)) {
            return Problem.INVALID_FRAGMENT
        }
        config.aetherFragmentSize = size?.toString()
        config.aetherFragmentDelay = delay?.toString()
        return null
    }

    private fun normalizeEndpoints(config: ProfileItem): Problem? {
        if (AetherProtocol.fromString(config.aetherProtocol).twoHops) {
            val outerText = config.aetherWiwOuter?.trim().orEmpty()
            val innerText = config.aetherWiwInner?.trim().orEmpty()
            val outer = AetherEndpoint.parse(outerText)
            val inner = AetherEndpoint.parse(innerText)
            if (outerText.isNotEmpty() && outer == null || innerText.isNotEmpty() && inner == null) {
                return Problem.INVALID_HOP
            }
            if (outer != null && inner != null && outer.host == inner.host) {
                return Problem.SHARED_HOP
            }
            config.aetherWiwOuter = outer?.toString()
            config.aetherWiwInner = inner?.toString()
            config.server = null
            config.serverPort = null
            return null
        }

        val address = config.server?.trim().orEmpty()
        val endpoint = AetherEndpoint.of(address, config.serverPort)
        if (address.isNotEmpty() && endpoint == null) {
            return Problem.INVALID_PEER
        }
        config.server = endpoint?.host
        config.serverPort = endpoint?.port?.toString()
        config.aetherWiwOuter = null
        config.aetherWiwInner = null
        return null
    }
}
