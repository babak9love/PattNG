package com.v2ray.ang.ui.server

import android.content.Context
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherIdentityManager
import com.v2ray.ang.core.AetherIdentityStatus
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.core.AetherScanner
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The daemon's live Aether session; [protocol] is null when only its listener could be seen. */
data class AetherSession(val protocol: AetherProtocol?) {

    /** A scan opens a second tunnel on the scanned protocol's key, which disturbs a session using that key. */
    fun disturbedByScanOf(protocol: AetherProtocol): Boolean =
        this.protocol == null || AetherIdentityManager.sharesIdentity(protocol, this.protocol)
}

interface AetherEditorSource {
    suspend fun isCoreAvailable(): Boolean

    /** Whether the Psiphon client is shipped with this build; a profile with Psiphon cannot connect without it. */
    suspend fun isPsiphonAvailable(): Boolean

    /** Whether the pluggable transport is shipped with this build; without it Tor has no bridges where it is blocked. */
    suspend fun isTorTransportsAvailable(): Boolean

    /** The daemon's live Aether session, scanning or connected, or null; every Aether profile shares its key files. */
    suspend fun activeSession(): AetherSession?
    suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult?
    suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus
    suspend fun renewIdentity(profile: ProfileItem, onOutput: (String) -> Unit): AetherIdentityStatus?
}

class AetherEditorRepository(private val context: Context) : AetherEditorSource {

    override suspend fun isCoreAvailable(): Boolean =
        withContext(Dispatchers.IO) { AetherCoreManager.isSupported(context) }

    override suspend fun isPsiphonAvailable(): Boolean =
        withContext(Dispatchers.IO) { AetherCoreManager.isPsiphonSupported(context) }

    override suspend fun isTorTransportsAvailable(): Boolean =
        withContext(Dispatchers.IO) { AetherCoreManager.isTorTransportsSupported(context) }

    // The daemon is the only authority on its state, so this looks for its core process and its
    // listener instead of a UI-side flag. The process check covers the scanning phase, before the
    // listener exists, and names the protocol; the listener probe is the fallback when /proc
    // cannot be read, and then the protocol stays unknown.
    override suspend fun activeSession(): AetherSession? = withContext(Dispatchers.IO) {
        AetherCoreManager.sessionProtocol(context)?.let { AetherSession(it) }
            ?: AetherSession(protocol = null).takeIf { AetherCoreManager.answersSocks(AetherCoreManager.socksPort) }
    }

    override suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult? =
        AetherScanner.scan(context, profile, onOutput)

    override suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus =
        AetherIdentityManager.status(context, protocol)

    override suspend fun renewIdentity(profile: ProfileItem, onOutput: (String) -> Unit): AetherIdentityStatus? =
        AetherIdentityManager.renew(context, profile, onOutput)
}
