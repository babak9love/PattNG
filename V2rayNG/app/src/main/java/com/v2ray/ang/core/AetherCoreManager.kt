package com.v2ray.ang.core

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.AetherRange
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherPsiphonMode
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Owns the Aether core process of the daemon: one live session at a time, an exit callback the
 * service reacts to, output relayed into the app log, readiness probing, and cleanup of leftover
 * processes. `service/ProcessService` is a fire-and-forget wrapper with none of that lifecycle,
 * which is why this is a separate owner rather than an extension of it.
 */
object AetherCoreManager {

    private const val BINARY_NAME = "libaether.so"

    /** The Psiphon client the core runs for a profile with Psiphon, shipped beside the core as a library. */
    private const val PSIPHON_BINARY_NAME = "libpsiphon-tunnel-core.so"

    /** The option that names Psiphon's own listener. */
    internal const val PSIPHON_BIND = "--psiphon-bind"
    private const val PROBE_TIMEOUT_MS = 1000
    private const val READY_POLL_MS = 500L
    private const val DEFAULT_LOG_LEVEL = "info"

    /**
     * Environment variable naming the app process that spawned a core process. Rust ignores
     * SIGPIPE and the core has no parent-death handling, so a core whose owner was killed keeps
     * running until something else kills it; [reapStale] recognises such orphans by this value.
     */
    internal const val OWNER_ENV = "PATTNG_AETHER_OWNER"

    /**
     * Environment variable set on the daemon's session core and on no other. A profile and a custom
     * configuration choose the port their core listens on, so the port does not tell the session
     * from a scan or a test core; this does, for the processes that have to leave the session's key
     * alone.
     */
    internal const val SESSION_ENV = "PATTNG_AETHER_SESSION"

    /** Environment variable that tells the core where the Psiphon client is; it looks for it under other names otherwise. */
    internal const val PSIPHON_BIN_ENV = "AETHER_PSIPHON_BIN"

    private val logLevels = setOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")
    private val procDir = File("/proc")

    private val lifecycle = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aether-core").apply { isDaemon = true }
    }

    /** The port a core listens on unless its profile or its custom configuration names another. */
    val socksPort: Int get() = AppConfig.PORT_AETHER_SOCKS.toInt()

    /** The loopback port the session core of [profile] listens on, and the one its SOCKS outbound dials. */
    fun listenPort(profile: ProfileItem): Int = AetherFmt.listenPortOf(profile.aetherListenPort) ?: socksPort

    @Volatile
    private var session: Session? = null

    val isRunning: Boolean get() = session != null

    fun isSupported(context: Context): Boolean = binary(context).canExecute()

    /** Whether this build ships the Psiphon client; without it a profile with Psiphon cannot connect. */
    fun isPsiphonSupported(context: Context): Boolean = psiphonBinary(context).canExecute()

    fun buildArguments(
        profile: ProfileItem,
        port: Int,
        scan: Boolean = false,
        logLevel: String = DEFAULT_LOG_LEVEL,
    ): List<String> {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        // A scan looks for WARP endpoints, which Psiphon has no part in.
        val psiphon = if (scan) AetherPsiphon.OFF else AetherPsiphon.fromString(profile.aetherPsiphon)
        return buildList {
            // With Psiphon inside the tunnel the app dials Psiphon on [port], and the tunnel's own
            // listener, which Psiphon leaves through, takes the port after it.
            val own = if (psiphon == AetherPsiphon.CHAIN) port + 1 else port
            addAll(listOf("--bind", "${AppConfig.LOOPBACK}:$own"))
            if (psiphon != AetherPsiphon.ONLY) {
                addAll(listOf("--protocol", protocol.type))
                addAll(listOf("--scan", AetherScanMode.fromString(profile.aetherScanMode).type))
                addAll(listOf("--noize", AetherObfuscation.fromString(profile.aetherObfuscation).type))
                addAll(listOf("--ip", AetherIpVersion.fromString(profile.aetherIpVersion).type))

                if (protocol.overMasque &&
                    AetherTransport.fromString(profile.aetherTransport) == AetherTransport.HTTP2
                ) {
                    add("--h2")
                    if (profile.aetherFragment == true) {
                        add("--fragment")
                        AetherRange.parse(profile.aetherFragmentSize, AetherRange.FRAGMENT_SIZE)
                            ?.let { addAll(listOf("--fragment-size", it.toString())) }
                        AetherRange.parse(profile.aetherFragmentDelay, AetherRange.FRAGMENT_DELAY)
                            ?.let { addAll(listOf("--fragment-delay", it.toString())) }
                    }
                }

                if (protocol.twoHops) {
                    val hop = if (protocol == AetherProtocol.MIM) "--mim" else "--wiw"
                    val outer = AetherEndpoint.parse(profile.aetherWiwOuter).takeUnless { scan }
                    val inner = AetherEndpoint.parse(profile.aetherWiwInner).takeUnless { scan }
                    outer?.let { addAll(listOf("$hop-outer", it.toString())) }
                    inner?.let { addAll(listOf("$hop-inner", it.toString())) }
                    if (outer == null && inner == null) add("$hop-scan")
                } else if (!scan) {
                    AetherEndpoint.of(profile.server, profile.serverPort)?.let { addAll(listOf("--peer", it.toString())) }
                }

                add(if (scan) "--no-quick-reconnect" else "--quick-reconnect")
            }

            when (psiphon) {
                AetherPsiphon.OFF -> Unit
                AetherPsiphon.CHAIN -> {
                    add("--psiphon")
                    addAll(listOf(PSIPHON_BIND, "${AppConfig.LOOPBACK}:$port"))
                }

                AetherPsiphon.REVERSE -> {
                    add("--psiphon-reverse")
                    // Nothing of the app dials Psiphon's own listener here, and the core takes the port Psiphon
                    // reports; an ephemeral port keeps a test core from colliding with the session's.
                    addAll(listOf(PSIPHON_BIND, "${AppConfig.LOOPBACK}:0"))
                }

                AetherPsiphon.ONLY -> add("--psiphon-only")
            }
            if (psiphon != AetherPsiphon.OFF) {
                addAll(listOf("--psiphon-mode", AetherPsiphonMode.fromString(profile.aetherPsiphonMode).type))
                profile.aetherPsiphonCdnIps?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--psiphon-cdn-ips", it)) }
                profile.aetherPsiphonCdnSni?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--psiphon-cdn-sni", it)) }
                profile.aetherPsiphonRegion?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--psiphon-region", it)) }
            }
            addAll(listOf("--log-level", logLevel))
        }
    }

    /**
     * Maps the app's core log level setting onto the levels the core accepts. Only the session
     * follows the setting: scans and key renewals keep the default because they read info lines.
     */
    internal fun coreLogLevel(appLevel: String?): String = when (appLevel?.lowercase(Locale.US)) {
        "debug" -> "debug"
        "info" -> "info"
        "warning", "warn" -> "warn"
        "error", "none" -> "error"
        else -> DEFAULT_LOG_LEVEL
    }

    /** [arguments] at [logLevel], unless they name a level of their own, as a hand-written command may. */
    internal fun withLogLevel(arguments: List<String>, logLevel: String): List<String> =
        if ("--log-level" in arguments || "--verbose" in arguments) arguments else arguments + listOf("--log-level", logLevel)

    internal fun startProcess(context: Context, arguments: List<String>, markSession: Boolean = false): Process {
        val workDir = AetherIdentityManager.workDir(context).apply { mkdirs() }
        val builder = ProcessBuilder(listOf(binary(context).absolutePath) + arguments)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put(OWNER_ENV, android.os.Process.myPid().toString())
            if (markSession) put(SESSION_ENV, "1")
            psiphonBinary(context).takeIf { it.canExecute() }?.let { put(PSIPHON_BIN_ENV, it.absolutePath) }
            put("HOME", workDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("AETHER_CONFIG", File(workDir, AetherIdentityManager.BASE_FILE).absolutePath)
            put("AETHER_MASQUE_CONFIG", File(workDir, AetherIdentityManager.MASQUE_FILE).absolutePath)
            put("AETHER_WG_CONFIG", File(workDir, AetherIdentityManager.WIREGUARD_FILE).absolutePath)
        }
        return builder.start()
    }

    internal suspend fun <T> withProcess(
        context: Context,
        arguments: List<String>,
        source: String,
        onOutput: (String) -> Unit,
        block: suspend (output: ReceiveChannel<String>) -> T?,
    ): T? = coroutineScope {
        // A cancellation can land while the spawn runs or while its result is on the way back to this
        // coroutine; either way the core would keep running with nobody holding its handle, so the
        // handle is kept aside and the core is destroyed on that path.
        val spawned = AtomicReference<Process?>()
        val process = try {
            withContext(Dispatchers.IO) {
                try {
                    reapStale(context, null)
                    startProcess(context, arguments).also(spawned::set)
                } catch (e: IOException) {
                    LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch $source", e)
                    null
                }
            }
        } catch (e: CancellationException) {
            spawned.get()?.destroy()
            throw e
        } ?: return@coroutineScope null

        val output = Channel<String>(Channel.UNLIMITED)
        launch(Dispatchers.IO) { forward(process, source, onOutput, output) }
        try {
            ensureActive()
            block(output)
        } finally {
            process.destroy()
        }
    }

    internal suspend fun <T : Any> runUntil(
        context: Context,
        arguments: List<String>,
        timeoutMs: Long,
        source: String,
        onOutput: (String) -> Unit,
        match: (String) -> T?,
    ): T? = withProcess(context, arguments, source, onOutput) { output ->
        withTimeoutOrNull(timeoutMs) { output.receiveAsFlow().mapNotNull(match).firstOrNull() }
    }

    /** Starts the session core [core], at the log level of the app setting unless its arguments name one. */
    @Synchronized
    fun start(context: Context, core: AetherCore, onExit: () -> Unit) {
        stop()
        val next = Session(core.port, onExit)
        session = next
        val appContext = context.applicationContext
        val logLevel = coreLogLevel(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL))
        val arguments = withLogLevel(core.arguments, logLevel)
        lifecycle.execute { open(next, appContext, arguments) }
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        session = null
        lifecycle.execute { current.process?.destroy() }
    }

    suspend fun awaitListening(timeoutMs: Long): Boolean =
        awaitReady(timeoutMs, READY_POLL_MS, { isRunning }, { session?.port?.let(::acceptsConnections) == true })

    internal suspend fun awaitReady(
        timeoutMs: Long,
        pollMs: Long,
        running: () -> Boolean,
        listening: () -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (running()) {
            if (withContext(Dispatchers.IO) { listening() }) return@withTimeoutOrNull true
            delay(pollMs)
        }
        false
    } ?: false

    /** What the daemon's warm-up wait ends with once it stops polling. */
    internal enum class WarmUpOutcome {
        /** The listener accepts connections; the profile can carry traffic. */
        LISTENING,

        /** The core exited before its listener came up while the service still runs. */
        CORE_EXITED,

        /** The wait was cancelled or the service stopped meanwhile; nothing is left to report. */
        ABANDONED,
    }

    /**
     * Decides what the warm-up wait reports. The exit callback cannot stop the service while
     * Xray is still starting, so a core that died in that window is caught here instead.
     */
    internal fun warmUpOutcome(listening: Boolean, active: Boolean, serviceRunning: Boolean): WarmUpOutcome = when {
        !active || !serviceRunning -> WarmUpOutcome.ABANDONED
        listening -> WarmUpOutcome.LISTENING
        else -> WarmUpOutcome.CORE_EXITED
    }

    internal fun acceptsConnections(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(AppConfig.LOOPBACK, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: IOException) {
        false
    }

    internal fun relay(line: String, source: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val message = "[$source] $text"
        when (outputPriority(text)) {
            Log.ERROR -> LogUtil.e(AppConfig.TAG, message)
            Log.WARN -> LogUtil.w(AppConfig.TAG, message)
            Log.DEBUG -> LogUtil.d(AppConfig.TAG, message)
            else -> LogUtil.i(AppConfig.TAG, message)
        }
    }

    internal fun outputPriority(line: String): Int {
        if (line.startsWith("Error:")) return Log.ERROR
        return when (logHeader(line)?.get(1)) {
            "ERROR" -> Log.ERROR
            "WARN" -> Log.WARN
            "DEBUG", "TRACE" -> Log.DEBUG
            else -> Log.INFO
        }
    }

    internal fun outputMessage(line: String): String {
        val text = line.trim()
        return if (logHeader(text) != null) text.substringAfter(']').trim() else text
    }

    private fun logHeader(line: String): List<String>? {
        if (!line.startsWith('[')) return null
        val headerEnd = line.indexOf(']').takeIf { it > 0 } ?: return null
        return line.substring(1, headerEnd)
            .split(' ')
            .filter(String::isNotEmpty)
            .takeIf { it.size >= 3 && it[1] in logLevels }
    }

    /**
     * Kills leftover core processes of this app: any whose owning app process is gone and, when
     * [bindAddress] is given, any still holding that listener address. Android can kill the
     * daemon, the editor or the test service without their child processes following, and a
     * survivor on the session port would otherwise make every later start fail until a reboot.
     */
    internal fun reapStale(context: Context, bindAddress: String?) {
        for (core in coreProcesses(context)) {
            if (!isStale(core.argv, core.ownerAlive, bindAddress)) continue
            LogUtil.w(
                AppConfig.TAG,
                "AetherCore: killing a leftover core process, pid=${core.pid} bind=${bindAddressOf(core.argv)} ownerAlive=${core.ownerAlive}"
            )
            android.os.Process.killProcess(core.pid)
        }
    }

    /**
     * The arguments of the daemon's live session core, without the binary, or null when no core
     * owned by a living app process is the session. They are read from /proc, so they are
     * available during the scanning phase before the listener exists, which is exactly when the
     * shared key files must not be replaced and no second tunnel must be opened on the same key.
     */
    fun sessionArguments(context: Context): List<String>? =
        coreProcesses(context).firstOrNull { isSession(it.argv, it.ownerAlive, it.sessionMarked, sessionAddress) }?.argv?.drop(1)

    /** The protocol of the daemon's live session, or null without one; see [sessionArguments]. */
    fun sessionProtocol(context: Context): AetherProtocol? = sessionArguments(context)?.let(::protocolOf)

    /**
     * True when [arguments] are those the daemon starts [profile] with, apart from the log level,
     * which follows a setting that can change while the session runs, and from the listener: the
     * same tunnel behind another port, as another profile or a custom configuration may run it,
     * serves the profile just as well. This is how another process tells the running profile from
     * a merely selected one.
     */
    fun runsProfile(arguments: List<String>, profile: ProfileItem): Boolean = AetherCore.of(profile).runsAs(arguments)

    /** [arguments] without the listeners and the log level: what tells one tunnel from another. */
    internal fun tunnelArguments(arguments: List<String>): List<String> =
        withoutOption(withoutOption(withoutOption(arguments, "--log-level"), "--bind"), PSIPHON_BIND)

    /** [arguments] without every [flag] and the value after it. */
    internal fun withoutOption(arguments: List<String>, flag: String): List<String> {
        val kept = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            if (arguments[index] == flag) index += 2 else kept.add(arguments[index++])
        }
        return kept
    }

    /** [arguments] with the listener [flag] names on the loopback port [port], in place of whatever they named for it. */
    internal fun withListener(arguments: List<String>, flag: String, port: Int): List<String> =
        withoutOption(arguments, flag) + listOf(flag, "${AppConfig.LOOPBACK}:$port")

    /** True when [arguments] run Psiphon inside the tunnel, where Psiphon's listener is the one the app dials. */
    internal fun dialsPsiphon(arguments: List<String>): Boolean = "--psiphon" in arguments

    /** A core process is stale when its owner is known to be dead or it holds the address we are about to bind. */
    internal fun isStale(argv: List<String>, ownerAlive: Boolean?, bindAddress: String?): Boolean =
        ownerAlive == false || (bindAddress != null && listenerAddressOf(argv) == bindAddress)

    /**
     * A core process counts as the session while its owner is not known to be dead and it carries
     * the session mark. When its environment could not be read, [sessionMarked] is null and the
     * address a session holds by default stands in for the mark.
     */
    internal fun isSession(argv: List<String>, ownerAlive: Boolean?, sessionMarked: Boolean?, sessionAddress: String): Boolean =
        ownerAlive != false && (sessionMarked ?: (listenerAddressOf(argv) == sessionAddress))

    private val sessionAddress: String get() = "${AppConfig.LOOPBACK}:$socksPort"

    /**
     * A core process of this app found in /proc; [ownerAlive] is null when its owner could not be
     * read, [sessionMarked] when its environment could not.
     */
    internal class CoreProcess(val pid: Int, val argv: List<String>, val ownerAlive: Boolean?, val sessionMarked: Boolean?)

    private fun coreProcesses(context: Context): List<CoreProcess> {
        val binary = binary(context).absolutePath
        val entries = procDir.listFiles() ?: return emptyList()
        return entries.mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            val argv = readNulSeparated(File(entry, "cmdline")) ?: return@mapNotNull null
            if (argv.firstOrNull() != binary) return@mapNotNull null
            val environ = readNulSeparated(File(entry, "environ"))
            val ownerAlive = ownerPid(environ)?.let { File(procDir, it.toString()).isDirectory }
            CoreProcess(pid, argv, ownerAlive, environ?.let(::isSessionMarked))
        }
    }

    internal fun bindAddressOf(argv: List<String>): String? = valueAfter(argv, "--bind")

    /** The port of the core's own listener, null when [argv] names none. */
    internal fun bindPortOf(argv: List<String>): Int? = portAfter(argv, "--bind")

    /** The address of the listener the app dials: Psiphon's when Psiphon runs inside the tunnel, the core's own otherwise. */
    internal fun listenerAddressOf(argv: List<String>): String? =
        valueAfter(argv, if (dialsPsiphon(argv)) PSIPHON_BIND else "--bind")

    /** The port of the listener the app dials, null when [argv] names none. */
    internal fun listenerPortOf(argv: List<String>): Int? =
        listenerAddressOf(argv)?.substringAfterLast(':', "")?.toIntOrNull()

    /** The port of the address after [flag], null when there is none or it cannot be read. */
    internal fun portAfter(argv: List<String>, flag: String): Int? =
        valueAfter(argv, flag)?.substringAfterLast(':', "")?.toIntOrNull()

    /**
     * The protocol [argv] selects, read the way the core reads it: the last of --protocol and the
     * protocol flags wins, a hop named without any of them selects the two-hop protocol it belongs
     * to, warp-in-warp before masque-in-masque, and nothing at all is masque.
     */
    internal fun protocolOf(argv: List<String>): AetherProtocol {
        var chosen: AetherProtocol? = null
        var wiwHopNamed = false
        var mimHopNamed = false
        for ((index, word) in argv.withIndex()) {
            when (word) {
                "--protocol" -> chosen = argv.getOrNull(index + 1)?.let(::protocolNamed) ?: chosen
                "--masque" -> chosen = AetherProtocol.MASQUE
                "--wg", "--wireguard", "--warp" -> chosen = AetherProtocol.WIREGUARD
                "--gool", "--wiw" -> chosen = AetherProtocol.GOOL
                "--mim", "--masque-in-masque" -> chosen = AetherProtocol.MIM
                "--wiw-outer", "--gool-outer", "--outer-peer", "--wiw-inner", "--gool-inner", "--inner-peer" -> wiwHopNamed = true
                "--wiw-peers", "--gool-peers" -> if (namesHops(argv.getOrNull(index + 1))) wiwHopNamed = true
                "--mim-outer", "--mim-inner" -> mimHopNamed = true
                "--mim-peers" -> if (namesHops(argv.getOrNull(index + 1))) mimHopNamed = true
            }
        }
        return chosen ?: when {
            wiwHopNamed -> AetherProtocol.GOOL
            mimHopNamed -> AetherProtocol.MIM
            else -> AetherProtocol.MASQUE
        }
    }

    /** The protocol the core selects for [name] after --protocol, under any of the names it accepts. */
    private fun protocolNamed(name: String): AetherProtocol = when (name.trim().lowercase(Locale.US)) {
        "wg", "wireguard" -> AetherProtocol.WIREGUARD
        "gool", "wiw", "warp-in-warp", "warpinwarp" -> AetherProtocol.GOOL
        "mim", "m2", "masque-in-masque", "masqueinmasque" -> AetherProtocol.MIM
        else -> AetherProtocol.MASQUE
    }

    /** Whether a value of --wiw-peers or --mim-peers names hops rather than asking for a scan, as the core reads it. */
    private fun namesHops(value: String?): Boolean = value != null && value.lowercase(Locale.US) !in scanKeywords

    /** The values of --wiw-peers and --mim-peers that ask for a scan instead of naming hops, as the core reads them. */
    private val scanKeywords = setOf("auto", "scan", "none", "off", "0")

    /** The value after the last [flag] in [argv]; the last one is the one the core keeps. */
    private fun valueAfter(argv: List<String>, flag: String): String? =
        argv.lastIndexOf(flag).takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    internal fun ownerPid(environ: List<String>?): Int? =
        environ?.firstOrNull { it.startsWith("$OWNER_ENV=") }?.substringAfter('=')?.toIntOrNull()

    internal fun isSessionMarked(environ: List<String>): Boolean = environ.any { it.startsWith("$SESSION_ENV=") }

    private fun readNulSeparated(file: File): List<String>? = try {
        file.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun psiphonBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, PSIPHON_BINARY_NAME)

    private fun open(target: Session, context: Context, arguments: List<String>) {
        if (session !== target) return
        reapStale(context, listenerAddressOf(arguments))
        val process = try {
            startProcess(context, arguments, markSession = true)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch the core", e)
            if (release(target)) target.onExit()
            return
        }
        target.process = process
        thread(name = "aether-core-output", isDaemon = true) { watch(target, process) }
    }

    private fun forward(process: Process, source: String, onOutput: (String) -> Unit, output: Channel<String>) {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                relay(line, source)
                onOutput(line)
                output.trySend(line)
            }
        } catch (e: IOException) {
            LogUtil.d(AppConfig.TAG, "AetherCore: $source output closed: ${e.message}")
        } finally {
            output.close()
        }
    }

    private fun watch(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { relay(it, "aether") }
        } catch (e: IOException) {
            LogUtil.d(AppConfig.TAG, "AetherCore: output closed: ${e.message}")
        }
        val exitCode = process.waitFor()
        if (!release(target)) return
        LogUtil.e(AppConfig.TAG, "AetherCore: the core exited on its own with code $exitCode")
        target.onExit()
    }

    @Synchronized
    private fun release(target: Session): Boolean {
        if (session !== target) return false
        session = null
        return true
    }

    private class Session(val port: Int, val onExit: () -> Unit) {
        var process: Process? = null
    }
}
