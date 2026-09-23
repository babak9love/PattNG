package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol

/**
 * The Aether core a configuration runs on, as the arguments its process is started with, the SOCKS
 * listener among them. The core of a profile is built from the profile's settings; the core of a
 * custom configuration is the command line it carries as aetherCommand, read as written and run as
 * written, so that what the configuration says is what runs. Two cores with the same arguments are
 * one core, which is how one process comes to serve several outbounds.
 */
data class AetherCore(val arguments: List<String>) {

    /** The loopback port the core listens on, the one the Aether outbounds of the configuration dial. */
    val port: Int get() = AetherCoreManager.bindPortOf(arguments) ?: AetherCoreManager.socksPort

    /** The protocol, which tells whose identity files the core uses. */
    val protocol: AetherProtocol get() = AetherCoreManager.protocolOf(arguments)

    /** The command line a custom configuration carries for this core; [ofCommand] reads it back. */
    val command: String get() = (listOf(COMMAND_NAME) + arguments).joinToString(" ", transform = ::quoted)

    /** This core behind a listener on [port] instead, as a latency test opens it on a port of its own. */
    fun on(port: Int): AetherCore = AetherCore(AetherCoreManager.withBind(arguments, port))

    /** True when a process started with [processArguments] runs this core, on whatever port and at whatever log level. */
    fun runsAs(processArguments: List<String>): Boolean =
        AetherCoreManager.tunnelArguments(processArguments) == AetherCoreManager.tunnelArguments(arguments)

    companion object {

        /** The name a command line starts with; the app runs its own copy of the core whatever the name says. */
        const val COMMAND_NAME = "aether"

        /** The core of [profile]: its settings as arguments, listening on its port. The log level is the session's to add. */
        fun of(profile: ProfileItem): AetherCore = AetherCore(
            AetherCoreManager.withoutOption(
                AetherCoreManager.buildArguments(profile, AetherCoreManager.listenPort(profile)),
                "--log-level",
            )
        )

        /**
         * The core [command] describes, or null when it names nothing the app can run: no argument
         * at all, or a --bind whose port cannot be read. Words are split on whitespace, quotes keep a
         * word together, and a program name in front is dropped. A command without --bind listens on
         * [AetherCoreManager.socksPort], the port the Aether outbounds of the app dial unless told
         * otherwise; the core's own default is another port, which nothing in the app dials.
         */
        fun ofCommand(command: String): AetherCore? {
            val words = words(command)
            val arguments = if (words.firstOrNull()?.startsWith("-") == false) words.drop(1) else words
            if (arguments.isEmpty()) return null
            if ("--bind" !in arguments) return AetherCore(AetherCoreManager.withBind(arguments, AetherCoreManager.socksPort))
            return AetherCore(arguments).takeIf { AetherCoreManager.bindPortOf(arguments) != null }
        }

        /** The words of a command line: split on whitespace, with single or double quotes keeping a word together. */
        internal fun words(command: String): List<String> {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var quote: Char? = null
            var open = false
            for (c in command) {
                when {
                    quote != null -> if (c == quote) quote = null else word.append(c)
                    c == '"' || c == '\'' -> {
                        quote = c
                        open = true
                    }

                    c.isWhitespace() -> if (open) {
                        words.add(word.toString())
                        word.setLength(0)
                        open = false
                    }

                    else -> {
                        word.append(c)
                        open = true
                    }
                }
            }
            if (open) words.add(word.toString())
            return words
        }

        /** [word] as a command line carries it: quoted when whitespace would split it. */
        private fun quoted(word: String): String = if (word.isEmpty() || word.any(Char::isWhitespace)) "\"$word\"" else word
    }
}
