package com.v2ray.ang.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt

/**
 * The Aether profile a configuration runs on. Every Aether outbound is a SOCKS connection to the
 * one core process the daemon starts, so a configuration can use one Aether profile, whether it is
 * the selected profile itself, the entry hop of a chain, a routing target or a policy-group member.
 * Two profiles count as the same one when the core would be started with the same arguments,
 * the port it listens on included.
 *
 * In a chain the Aether hop can only be the entry hop, the one that dials the internet itself:
 * another hop can dial through it, but it cannot dial through anything, since its outbound only
 * reaches the core on the loopback address.
 *
 * A custom configuration asks for its core itself, with aetherSettings in the settings of one SOCKS
 * outbound, and the core listens on the port that outbound dials, as a profile names its own.
 */
sealed interface AetherDependency {

    /** No Aether outbound anywhere in the configuration. */
    data object None : AetherDependency

    /** Exactly one Aether profile; the daemon starts the core with it, on [AetherCoreManager.listenPort]. */
    data class Single(val profile: ProfileItem) : AetherDependency

    /** Aether profiles with different settings, which one core cannot serve. */
    data object Conflicting : AetherDependency

    /** An Aether profile in a chain position other than the entry hop. */
    data class NotEntryHop(val chainTag: String) : AetherDependency

    /** A custom configuration with aetherSettings in more than one outbound, while one core runs. */
    data object SeveralCores : AetherDependency

    /** A custom configuration whose outbound with aetherSettings does not dial a port of [AppConfig.LOOPBACK], where the core listens. */
    data object NoListener : AetherDependency

    /** A custom configuration whose aetherSettings cannot start a core; [reason] names what. */
    data class UnusableSettings(val reason: AetherFmt.Settings.Invalid) : AetherDependency

    companion object {

        private const val SETTINGS_KEY = "aetherSettings"

        /** How much of a value that is no settings object at all is quoted back in the error. */
        private const val UNKNOWN_ENTRY_LENGTH = 40

        /**
         * [outbounds] are the resolved outbounds of a configuration. A chain's profiles are in
         * reverse dial order: the first is the exit, the last is the entry hop.
         */
        fun of(outbounds: List<CoreConfigContext.ResolvedOutbound>): AetherDependency {
            var found: ProfileItem? = null
            var foundArguments: List<String>? = null
            for (outbound in outbounds) {
                val profiles = outbound.resolvedProfiles
                for ((index, profile) in profiles.withIndex()) {
                    if (profile.configType != EConfigType.AETHER) continue
                    if (outbound.resolvedType == CoreResolvedType.PROXYCHAIN && index != profiles.lastIndex) {
                        return NotEntryHop(outbound.tag)
                    }
                    val arguments = AetherCoreManager.buildArguments(profile, AetherCoreManager.listenPort(profile))
                    if (found == null) {
                        found = profile
                        foundArguments = arguments
                    } else if (arguments != foundArguments) {
                        return Conflicting
                    }
                }
            }
            return found?.let(::Single) ?: None
        }

        /**
         * [config] is a custom configuration. Its core is the one described by the aetherSettings of
         * a SOCKS outbound, and the port of that outbound is where the core listens: the profile the
         * core is started with gets it as its listen port.
         */
        fun ofCustom(config: JsonObject): AetherDependency {
            val carriers = aetherOutboundSettings(config)
            val settings = carriers.singleOrNull() ?: return if (carriers.isEmpty()) None else SeveralCores
            val port = settings.get("port")?.let(::portOf)
            if (port == null || settings.get("address")?.let(::textOf) != AppConfig.LOOPBACK) return NoListener
            val written = settings.get(SETTINGS_KEY)
            val aetherSettings = written.takeIf { it.isJsonObject }?.asJsonObject
                ?: return UnusableSettings(AetherFmt.Settings.Unknown(written.toString().take(UNKNOWN_ENTRY_LENGTH)))
            return when (val parsed = AetherFmt.fromSettings(aetherSettings)) {
                is AetherFmt.Settings.Valid -> Single(parsed.profile.apply { aetherListenPort = AetherFmt.storedListenPort(port) })
                is AetherFmt.Settings.Invalid -> UnusableSettings(parsed)
            }
        }

        /**
         * Points the Aether outbound of the custom configuration [config] at [port], together with
         * any other SOCKS outbound dialing the same core. A latency test does this when it opens a
         * core of its own, which listens on a port of its own.
         */
        fun rebindCustom(config: JsonObject, from: Int, port: Int) {
            for (settings in socksOutboundSettings(config)) {
                if (settings.get("address")?.let(::textOf) == AppConfig.LOOPBACK && settings.get("port")?.let(::portOf) == from) {
                    settings.addProperty("port", port)
                }
            }
        }

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
