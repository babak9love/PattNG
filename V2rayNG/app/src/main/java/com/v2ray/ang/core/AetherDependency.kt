package com.v2ray.ang.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt

/**
 * The Aether core a configuration runs on. Every Aether outbound is a SOCKS connection to the one
 * core process the daemon starts, so a configuration can use one core, whether it is the selected
 * profile itself, the entry hop of a chain, a routing target or a policy-group member. Two profiles
 * count as the same core when it would be started with the same arguments, the port it listens on
 * included.
 *
 * In a chain the Aether hop can only be the entry hop, the one that dials the internet itself:
 * another hop can dial through it, but it cannot dial through anything, since its outbound only
 * reaches the core on the loopback address.
 *
 * A custom configuration asks for its core itself, with the command line of the core as
 * aetherCommand at its top level, and the SOCKS outbounds that dial the port that command listens
 * on are its Aether outbounds. Before that, the settings of the core were written as aetherSettings
 * into such an outbound; a configuration written that way is still read.
 */
sealed interface AetherDependency {

    /** No Aether outbound anywhere in the configuration. */
    data object None : AetherDependency

    /** Exactly one Aether core; the daemon starts it, and the Aether outbounds dial its [AetherCore.port]. */
    data class Single(val core: AetherCore) : AetherDependency

    /** Aether profiles with different settings, which one core cannot serve. */
    data object Conflicting : AetherDependency

    /** An Aether profile in a chain position other than the entry hop. */
    data class NotEntryHop(val chainTag: String) : AetherDependency

    /**
     * A custom configuration whose aetherCommand is no command line the app can run; [written] quotes
     * it for the screen. It stays out of the log, where what was written could carry a secret.
     */
    data class UnusableCommand(val written: String) : AetherDependency {
        override fun toString(): String = "UnusableCommand"
    }

    /** A custom configuration whose aetherCommand listens on [port] of [AppConfig.LOOPBACK], which none of its SOCKS outbounds dials. */
    data class NoOutbound(val port: Int) : AetherDependency

    /** A custom configuration whose outbounds carry aetherSettings for different cores, or for one core on different ports. */
    data object SeveralCores : AetherDependency

    /** A custom configuration whose outbound with aetherSettings does not dial a port of [AppConfig.LOOPBACK], where the core listens. */
    data object NoListener : AetherDependency

    /** A custom configuration whose aetherSettings cannot start a core; [reason] names what. */
    data class UnusableSettings(val reason: AetherFmt.Settings.Invalid) : AetherDependency

    companion object {

        /** The key of a custom configuration that carries the command line of its core. */
        const val COMMAND_KEY = "aetherCommand"

        private const val SETTINGS_KEY = "aetherSettings"

        /** How much of a value that is no command or no settings object at all is quoted back in the error. */
        private const val QUOTED_LENGTH = 40

        /**
         * [outbounds] are the resolved outbounds of a configuration. A chain's profiles are in
         * reverse dial order: the first is the exit, the last is the entry hop.
         */
        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): AetherDependency {
            var found: AetherCore? = null
            for (outbound in outbounds) {
                val profiles = outbound.resolvedProfiles
                for ((index, profile) in profiles.withIndex()) {
                    if (profile.configType != EConfigType.AETHER) continue
                    if (outbound.resolvedType == CoreResolvedType.PROXYCHAIN && index != profiles.lastIndex) {
                        return NotEntryHop(outbound.tag)
                    }
                    val core = AetherCore.of(profile)
                    if (found == null) {
                        found = core
                    } else if (core != found) {
                        return Conflicting
                    }
                }
            }
            return found?.let(::Single) ?: None
        }

        /**
         * [config] is a custom configuration. Its core is the command line at its aetherCommand key,
         * and the SOCKS outbounds dialing the port that command listens on are the ones the core
         * serves; there has to be at least one, or the core would run for nothing. Without that key,
         * the aetherSettings of a SOCKS outbound are read the way they were before it existed.
         */
        fun ofCustom(config: JsonObject): AetherDependency {
            val written = config.get(COMMAND_KEY)?.takeUnless { it.isJsonNull } ?: return ofSettings(config)
            val text = textOf(written) ?: return UnusableCommand(written.toString().take(QUOTED_LENGTH))
            val core = AetherCore.ofCommand(text) ?: return UnusableCommand(text.take(QUOTED_LENGTH))
            if (socksOutboundSettings(config).none { dials(it, core.port) }) return NoOutbound(core.port)
            return Single(core)
        }

        /**
         * The form before aetherCommand: the core described by the aetherSettings of a SOCKS outbound,
         * listening on the port that outbound dials. Several outbounds may carry aetherSettings as long
         * as they describe that same core, which is the rule [of] applies to profiles.
         */
        private fun ofSettings(config: JsonObject): AetherDependency {
            var found: AetherCore? = null
            for (settings in aetherOutboundSettings(config)) {
                val port = settings.get("port")?.let(::portOf)
                if (port == null || settings.get("address")?.let(::textOf) != AppConfig.LOOPBACK) return NoListener
                val written = settings.get(SETTINGS_KEY)
                val aetherSettings = written.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return UnusableSettings(AetherFmt.Settings.Unknown(written.toString().take(QUOTED_LENGTH)))
                val profile = when (val parsed = AetherFmt.fromSettings(aetherSettings)) {
                    is AetherFmt.Settings.Valid -> parsed.profile.apply { aetherListenPort = AetherFmt.storedListenPort(port) }
                    is AetherFmt.Settings.Invalid -> return UnusableSettings(parsed)
                }
                val core = AetherCore.of(profile)
                if (found == null) {
                    found = core
                } else if (core != found) {
                    return SeveralCores
                }
            }
            return found?.let(::Single) ?: None
        }

        /**
         * Points the Aether outbounds of the custom configuration [config], the SOCKS outbounds
         * dialing the core on [from], at [port] instead, and the command that names the listener
         * with them. A latency test does this when it opens a core of its own, which listens on a
         * port of its own.
         */
        fun rebindCustom(config: JsonObject, from: Int, port: Int) {
            for (settings in socksOutboundSettings(config)) {
                if (dials(settings, from)) settings.addProperty("port", port)
            }
            val core = config.get(COMMAND_KEY)?.let(::textOf)?.let(AetherCore::ofCommand) ?: return
            if (core.port == from) config.addProperty(COMMAND_KEY, core.on(port).command)
        }

        /**
         * True when an inbound of the configuration [content], as it is handed to Xray, listens on
         * [port]. The Aether core has to listen there on the loopback address, and Xray comes first:
         * the core only binds once its tunnel is up. The Aether outbound would then dial the inbound of
         * its own configuration until the core gives up. This covers what the profile editor cannot
         * see: a local proxy port changed later or picked at random, an imported profile, and the
         * inbounds of a custom configuration.
         */
        fun inboundListensOn(content: String, port: Int): Boolean {
            val config = try {
                JsonParser.parseString(content).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: JsonParseException) {
                null
            }
            val inbounds = config?.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
            return inbounds.any { inbound ->
                inbound.isJsonObject && inboundPorts(inbound.asJsonObject.get("port")).any { port in it }
            }
        }

        /** The ports of an inbound: a number, or the text forms Xray reads, such as "1080", "1000-2000" and "53,443,1000-2000". */
        private fun inboundPorts(element: JsonElement?): List<IntRange> {
            val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return emptyList()
            return primitive.asString.split(',').mapNotNull { part ->
                val bounds = part.split('-').map { it.trim().toIntOrNull() ?: return@mapNotNull null }
                if (bounds.size in 1..2) bounds.min()..bounds.max() else null
            }
        }

        /** True when the SOCKS outbound with [settings] dials the core's listener on [port]. */
        private fun dials(settings: JsonObject, port: Int): Boolean =
            settings.get("address")?.let(::textOf) == AppConfig.LOOPBACK && settings.get("port")?.let(::portOf) == port

        /** The settings of every SOCKS outbound of [config] that carries aetherSettings; a JSON null counts as left out. */
        private fun aetherOutboundSettings(config: JsonObject): List<JsonObject> =
            socksOutboundSettings(config).filter { it.get(SETTINGS_KEY)?.isJsonNull == false }

        private fun socksOutboundSettings(config: JsonObject): List<JsonObject> {
            val outbounds = config.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return outbounds.mapNotNull { element ->
                val outbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val protocol = outbound.get("protocol")?.let(::textOf)
                if (!protocol.equals(EConfigType.SOCKS.name, ignoreCase = true)) return@mapNotNull null
                outbound.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject
            }
        }

        private fun textOf(element: JsonElement): String? =
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()

        /** The port of a SOCKS outbound: a JSON number, which is all Xray reads there. */
        private fun portOf(element: JsonElement): Int? =
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
