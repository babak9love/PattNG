package com.v2ray.ang.enums

enum class AetherProtocol(val type: String) {
    MASQUE("masque"),
    WIREGUARD("wg"),
    GOOL("gool"),
    MIM("mim");

    /** Whether MASQUE carries the tunnel, which then uses the MASQUE transport, fragmentation and key. */
    val overMasque: Boolean get() = this == MASQUE || this == MIM

    /** Whether the tunnel is two hops, an outer and an inner one, in place of one endpoint. */
    val twoHops: Boolean get() = this == GOOL || this == MIM

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: MASQUE
    }
}

enum class AetherTransport(val type: String) {
    HTTP3("h3"),
    HTTP2("h2");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: HTTP3
    }
}

enum class AetherScanMode(val type: String) {
    TURBO("turbo"),
    BALANCED("balanced"),
    THOROUGH("thorough"),
    VERIFIED("verified"),
    IRONCLAD("ironclad");

    companion object {
        /** The name the verified mode had before aether 2.1; profiles, links and configurations from then still carry it. */
        const val STEALTH = "stealth"

        fun fromString(type: String?) = entries.find { it.type == type } ?: if (type == STEALTH) VERIFIED else BALANCED
    }
}

/**
 * The obfuscation profile, named the way the core names it. [AUTO] leaves the choice to the core,
 * which takes firewall for MASQUE and balanced for WireGuard and gool.
 */
enum class AetherObfuscation(val type: String) {
    AUTO("auto"),
    OFF("off"),
    LIGHT("light"),
    FIREWALL("firewall"),
    BALANCED("balanced"),
    GFW("gfw"),
    AGGRESSIVE("aggressive");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}

/**
 * The IP versions the core scans and connects over; IPv4 unless a profile says otherwise, so that a
 * profile connects on an IPv4-only network and on a dual-stack one alike.
 */
enum class AetherIpVersion(val type: String) {
    V4("v4"),
    V6("v6"),
    DUAL("both");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: V4
    }
}

/** Where Psiphon stands in the tunnel of a profile, named the way the core names it. */
enum class AetherPsiphon(val type: String) {
    OFF("off"),

    /** The tunnel carries Psiphon: the app dials Psiphon, which leaves through WARP. */
    CHAIN("chain"),

    /** Psiphon carries the tunnel: WARP is reached through Psiphon, and the app dials WARP. */
    REVERSE("reverse"),

    /** No WARP at all: the app dials Psiphon itself. */
    ONLY("only");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: OFF
    }
}

/**
 * The lists of CDN edges built into Psiphon's client, named the way its config names them and in
 * the order the app hands them over, which is the order the scan tries them.
 */
enum class AetherPsiphonCdnSet(val type: String) {
    CLOUDFLARE("cloudflare"),
    FASTLY("fastly"),
    CLOUDFRONT("cloudfront"),
    AKAMAI("psiphon-akamai"),
    BUNNY("psiphon-bunny"),
    VERCEL("vercel"),
    GITHUB("github"),
    CURATED("curated-fronting"),
    LEGACY("legacy-android-overrides");

    companion object {
        /** The sets named in [text], a comma or space separated list: in the order above, once each, strangers left out. */
        fun parse(text: String?): List<AetherPsiphonCdnSet> {
            val named = text.orEmpty().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.toSet()
            return entries.filter { it.type in named }
        }

        /** [sets] as the core and the profile take them, comma separated; null for none. */
        fun join(sets: Collection<AetherPsiphonCdnSet>): String? =
            entries.filter { it in sets }.joinToString(",") { it.type }.ifEmpty { null }
    }
}

/** How Psiphon reaches its servers. */
enum class AetherPsiphonMode(val type: String) {
    AUTO("auto"),
    CDN("cdn"),
    DIRECT("direct");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}

/** Where Tor stands in the tunnel of a profile, named the way the core names it: the same three places as [AetherPsiphon]. */
enum class AetherTor(val type: String) {
    OFF("off"),

    /** The tunnel carries Tor: the app dials Tor, which leaves through WARP, so the network never sees Tor. */
    CHAIN("chain"),

    /** Tor carries the tunnel: WARP is reached from a Tor exit, and the app dials WARP. */
    REVERSE("reverse"),

    /** No WARP at all: the app dials Tor itself. */
    ONLY("only");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: OFF
    }
}

/** When Tor turns to bridges. */
enum class AetherTorBridges(val type: String) {
    /** Tor is tried plainly first, and bridges are fetched when that gets nowhere. */
    AUTO("auto"),

    /** Bridges from the start, without trying Tor plainly. */
    FIRST("first"),

    /** Never, however blocked the network looks. */
    NEVER("never"),

    /** The bridge lines of the profile, and no other. */
    OWN("own");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}

/** Where Tor's fetched bridges come from: bridgedb, the public relays onionoo lists used as plain bridges, or both. */
enum class AetherTorRelays(val type: String) {
    /** bridgedb and the relays together, the core's own choice. */
    AUTO("auto"),

    /** The relays alone; bridgedb hands out few bridges, and they are blocked early. */
    ONLY("only"),

    /** bridgedb alone. */
    OFF("off");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}
