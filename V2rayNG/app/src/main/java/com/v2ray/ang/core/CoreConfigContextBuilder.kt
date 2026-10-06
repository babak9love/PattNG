package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * Build runtime context from the selected profile.
 *
 * All outbound type analysis is completed here for both the selected profile
 * and routing targets. Custom profiles are returned immediately without
 * entering the normal analysis flow.
 */
object CoreConfigContextBuilder {

    /**
     * Load one profile and produce a fully analyzed context.
     *
     * Null is returned only when the selected profile cannot be loaded.
     */
    fun build(context: Context, guid: String): CoreConfigContext? {
        val config = MmkvManager.decodeServerConfig(guid) ?: return null

        // CUSTOM: return immediately — CoreConfigManager handles this path on its own.
        if (config.configType == EConfigType.CUSTOM) {
            return CoreConfigContext(context = context, guid = guid, isCustom = true)
        }

        // Step 1: Resolve the main outbound (always tag = TAG_PROXY).
        val primaryResolvedOutbound = resolveOutbound(AppConfig.TAG_PROXY, config) ?: run {
            LogUtil.e(AppConfig.TAG, "Failed to resolve main outbound for '${config.remarks}'")
            return null
        }

        // Step 2: Resolve all non-builtin routing outbound tags.
        val (routingResolvedOutbounds, unresolvedRoutingTarget) = resolveRoutingOutbounds()
        val resolvedOutbounds = listOf(primaryResolvedOutbound) + routingResolvedOutbounds
        val (fallbackResolvedOutbounds, unresolvedFallback) = resolveFallbackOutbounds(resolvedOutbounds)
        val routingDomainRules = collectRoutingDomainRulesForDns()

        return CoreConfigContext(
            context = context,
            guid = guid,
            resolvedOutbounds = resolvedOutbounds + fallbackResolvedOutbounds,
            routingDomainRules = routingDomainRules,
            unresolvedTarget = unresolvedRoutingTarget ?: unresolvedFallback,
        )
    }

    /**
     * Resolve one outbound target into a normalized outbound entry.
     *
     * Custom profiles are ignored at this stage and produce no entry.
     */
    private fun resolveOutbound(tag: String, profile: ProfileItem): CoreConfigContext.ResolvedOutbound? {
        if (profile.configType == EConfigType.CUSTOM) {
            return null
        }

        var unresolvedHop: CoreConfigContext.UnresolvedName? = null
        val (resolvedProfiles, resolvedType) = when (profile.configType) {
            EConfigType.POLICYGROUP -> Pair(
                resolvePolicyGroupProfiles(profile),
                CoreResolvedType.POLICYGROUP,
            )

            EConfigType.PROXYCHAIN -> {
                val (chainProfiles, unresolved) = resolveProxyChainProfiles(profile)
                unresolvedHop = unresolved
                val type = if (chainProfiles.size <= 1) CoreResolvedType.NORMAL else CoreResolvedType.PROXYCHAIN
                Pair(chainProfiles, type)
            }

            else -> {
                val (chainProfiles, unresolved) = resolveProxyChainProfilesFromGroup(profile)
                unresolvedHop = unresolved
                val type = if (chainProfiles.size <= 1) CoreResolvedType.NORMAL else CoreResolvedType.PROXYCHAIN
                Pair(chainProfiles, type)
            }
        }

        return CoreConfigContext.ResolvedOutbound(
            tag = tag,
            profile = profile,
            resolvedProfiles = resolvedProfiles,
            resolvedType = resolvedType,
            unresolvedHop = unresolvedHop,
        )
    }

    /**
     * Collect and resolve non-builtin routing targets from enabled rules.
     *
     * Invalid or empty targets are skipped and handled by fallback logic later. PattNG: a target is a profile's name;
     * the first one that no profile has any more, or several have, goes beside them, for the session to be refused for
     * it rather than send the rule's traffic by the proxy, or by a profile it may not mean.
     */
    private fun resolveRoutingOutbounds(): Pair<List<CoreConfigContext.ResolvedOutbound>, CoreConfigContext.UnresolvedName?> {
        val rulesetItems = MmkvManager.decodeRoutingRulesets() ?: return emptyList<CoreConfigContext.ResolvedOutbound>() to null
        val resolvedOutbounds = mutableListOf<CoreConfigContext.ResolvedOutbound>()
        val processedTags = mutableSetOf<String>()
        var unresolved: CoreConfigContext.UnresolvedName? = null

        try {
            rulesetItems
                .filter { it.enabled }
                .mapNotNull { it.outboundTag.takeIf { tag -> tag.isNotBlank() } }
                .filter { tag -> tag !in AppConfig.BUILTIN_OUTBOUND_TAGS }
                .distinct()
                .forEach { tag ->
                    if (tag in processedTags) {
                        return@forEach
                    }
                    processedTags.add(tag)

                    try {
                        val profile = when (val found = SettingsManager.findServerViaRemarks(tag, ::takesAsRoutingTarget)) {
                            is ByName.One -> found.value
                            ByName.None, ByName.Several -> {
                                val several = found == ByName.Several
                                LogUtil.w(AppConfig.TAG, "Routing tag '$tag' has ${if (several) "several matching profiles" else "no matching profile"}; the session is refused")
                                if (unresolved == null) unresolved = CoreConfigContext.UnresolvedName(tag.trim(), several)
                                return@forEach
                            }
                        }
                        val resolvedOutbound = resolveOutbound(tag, profile) ?: run {
                            LogUtil.w(AppConfig.TAG, "Cannot use CUSTOM profile as routing outbound for tag '$tag', skipping")
                            return@forEach
                        }
                        // PattNG: a chain that names a hop no profile has, or several have, stays, for the configuration to be refused for it.
                        if (resolvedOutbound.resolvedProfiles.isEmpty() && resolvedOutbound.unresolvedHop == null) {
                            LogUtil.w(AppConfig.TAG, "Routing outbound '$tag' resolved to empty list, skipping")
                            return@forEach
                        }
                        resolvedOutbounds.add(resolvedOutbound)
                        LogUtil.d(AppConfig.TAG, "Resolved routing outbound: tag='$tag', type='${resolvedOutbound.resolvedType}', profiles=${resolvedOutbound.resolvedProfiles.size}")
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to resolve routing outbound for tag '$tag', skipping", e)
                    }
                }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve routing outbounds from rulesets", e)
        }

        return resolvedOutbounds to unresolved
    }

    /** PattNG: whether [profile] can be what a routing rule sends to: any profile but a custom configuration. */
    internal fun takesAsRoutingTarget(profile: ProfileItem): Boolean = profile.configType != EConfigType.CUSTOM

    private fun resolvePolicyGroupProfiles(config: ProfileItem): List<ProfileItem> {
        try {
            val serverList = MmkvManager.decodeAllServerList()
            return serverList
                .asSequence()
                .mapNotNull { id -> MmkvManager.decodeServerConfig(id) }
                .filter { profile ->
                    val subscriptionId = config.policyGroupSubscriptionId
                    if (subscriptionId.isNullOrBlank()) {
                        true
                    } else {
                        profile.subscriptionId == subscriptionId
                    }
                }
                .filter { profile ->
                    val filter = config.policyGroupFilter
                    if (filter.isNullOrBlank()) {
                        true
                    } else {
                        try {
                            Regex(filter).containsMatchIn(profile.remarks)
                        } catch (_: Exception) {
                            profile.remarks.contains(filter)
                        }
                    }
                }
                .filter { it.hasDialableServer() }
                .filter { !it.configType.isComplexType() }
                .toList()
                .let { members ->
                    val (kept, leftOut) = withOneAetherProfile(members)
                    leftOut.forEach {
                        LogUtil.w(AppConfig.TAG, "Policy group '${config.remarks}' leaves out '${it.remarks}': a second Aether profile with other settings, and one core serves one profile")
                    }
                    kept
                }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve policy group profiles for '${config.remarks}'", e)
            return listOf(config)
        }
    }

    /**
     * PattNG: the hops of the proxy chain [config], from the exit to the entry hop, found by their names, see
     * [proxyChainHops], and the first name that finds no profile, or several, beside them: the configuration is
     * refused for it, rather than run without that hop, or through one it may not mean.
     */
    private fun resolveProxyChainProfiles(config: ProfileItem): Pair<List<ProfileItem>, CoreConfigContext.UnresolvedName?> {
        if (config.proxyChainProfiles.isNullOrBlank()) {
            return listOf(config) to null
        }

        try {
            val (hops, unresolved) = proxyChainHops(config.proxyChainProfiles.orEmpty().split(",")) { name ->
                SettingsManager.findServerViaRemarks(name, ::takesAsHop)
            }
            return hops.filter { it.hasDialableServer() }.reversed() to unresolved
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve proxy chain profiles for '${config.remarks}'", e)
            return listOf(config) to null
        }
    }

    /** PattNG: whether [profile] can be a hop of a proxy chain: no chain, group or custom configuration of its own. */
    internal fun takesAsHop(profile: ProfileItem): Boolean = !profile.configType.isComplexType()

    /**
     * PattNG: the profiles [names], the hops of a proxy chain in the order it lists them, find through [find], see
     * [ByName], and the first of the names that finds none, or several. A blank name names no hop.
     */
    internal fun proxyChainHops(
        names: List<String>,
        find: (String) -> ByName<ProfileItem>,
    ): Pair<List<ProfileItem>, CoreConfigContext.UnresolvedName?> {
        val hops = mutableListOf<ProfileItem>()
        var unresolved: CoreConfigContext.UnresolvedName? = null
        for (name in names.map(String::trim).filter(String::isNotEmpty)) {
            when (val found = find(name)) {
                is ByName.One -> hops += found.value
                ByName.None -> if (unresolved == null) unresolved = CoreConfigContext.UnresolvedName(name, several = false)
                ByName.Several -> if (unresolved == null) unresolved = CoreConfigContext.UnresolvedName(name, several = true)
            }
        }
        return hops to unresolved
    }

    /**
     * PattNG: true when [profile], selected, runs as a chain with the hops its subscription puts
     * around every one of its profiles, or would, but for a hop its subscription names that no
     * profile, or several, have, for which it is refused as a chain; see [resolveProxyChainProfilesFromGroup].
     */
    internal fun isChained(profile: ProfileItem): Boolean =
        resolveProxyChainProfilesFromGroup(profile).let { (profiles, unresolved) -> profiles.size > 1 || unresolved != null }

    /**
     * Resolve chain nodes from subscription neighbors in order: next, current, prev.
     *
     * When no chain is available, return a single-node result. PattNG: the neighbors are found by their names, as the
     * hops of a chain profile are, see [proxyChainHops]; the first name that finds no profile, or several, goes beside
     * them, and the configuration is refused for it.
     */
    private fun resolveProxyChainProfilesFromGroup(config: ProfileItem): Pair<List<ProfileItem>, CoreConfigContext.UnresolvedName?> {
        if (config.subscriptionId.isEmpty()) {
            return listOf(config) to null
        }

        try {
            val subItem = MmkvManager.decodeSubscription(config.subscriptionId) ?: return listOf(config) to null
            val find = { name: String -> SettingsManager.findServerViaRemarks(name, ::takesAsHop) }
            val (next, nextUnresolved) = proxyChainHops(listOfNotNull(subItem.nextProfile), find)
            val (prev, prevUnresolved) = proxyChainHops(listOfNotNull(subItem.prevProfile), find)
            return next + config + prev to (nextUnresolved ?: prevUnresolved)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve proxy chain from group for '${config.remarks}'", e)
            return listOf(config) to null
        }
    }

    /**
     * Collect enabled routing domain rules in original order for DNS segmentation.
     *
     * outbounds are normalized into three tags only: proxy / direct / block.
     */
    private fun collectRoutingDomainRulesForDns(): List<CoreConfigContext.RoutingDomainRule> {
        val rulesetItems = MmkvManager.decodeRoutingRulesets() ?: return emptyList()
        val result = mutableListOf<CoreConfigContext.RoutingDomainRule>()

        rulesetItems
            .asSequence()
            .filter { it.enabled }
            .filter { !it.domain.isNullOrEmpty() }
            .forEach { rule ->
                val normalizedOutboundTag = when (rule.outboundTag) {
                    AppConfig.TAG_DIRECT -> AppConfig.TAG_DIRECT
                    AppConfig.TAG_BLOCKED -> AppConfig.TAG_BLOCKED
                    else -> AppConfig.TAG_PROXY
                }
                result.add(
                    CoreConfigContext.RoutingDomainRule(
                        domain = rule.domain.orEmpty(),
                        outboundTag = normalizedOutboundTag
                    )
                )
            }

        return result
    }

    /**
     * Resolve and collect fallback outbounds from all POLICYGROUP nodes.
     *
     * Fallback targets must not overlap with already resolved tags or builtin tags. PattNG: a target is a profile's
     * name; the first one that no profile has any more, or several have, goes beside them, for the session to be
     * refused for it rather than fall back to an outbound that is not there, or to a profile it may not mean.
     */
    private fun resolveFallbackOutbounds(
        resolvedOutbounds: List<CoreConfigContext.ResolvedOutbound>,
    ): Pair<List<CoreConfigContext.ResolvedOutbound>, CoreConfigContext.UnresolvedName?> {
        var unresolved: CoreConfigContext.UnresolvedName? = null
        val fallbacks = resolvedOutbounds
            .asSequence()
            .filter { it.resolvedType == CoreResolvedType.POLICYGROUP }
            .filter { BalancerStrategyType.from(it.profile.policyGroupType).supportsObservatory && it.profile.policyGroupTestOutbounds != false }
            .mapNotNull { it.profile.policyGroupFallbackTag?.takeIf(String::isNotBlank) }
            .filter { it !in AppConfig.BUILTIN_OUTBOUND_TAGS && resolvedOutbounds.none { outbound -> outbound.tag == it } }
            .distinct()
            .mapNotNull { tag ->
                when (val found = SettingsManager.findServerViaRemarks(tag, ::takesAsFallback)) {
                    is ByName.One -> resolveOutbound(tag, found.value)
                    ByName.None, ByName.Several -> {
                        LogUtil.w(AppConfig.TAG, "Policy group fallback '$tag' has ${if (found == ByName.Several) "several matching profiles" else "no matching profile"}; the session is refused")
                        if (unresolved == null) unresolved = CoreConfigContext.UnresolvedName(tag.trim(), found == ByName.Several)
                        null
                    }
                }
            }
            .toList()
        return fallbacks to unresolved
    }

    /** PattNG: whether [profile] can be the fallback of a policy group: any profile but a group or a custom configuration. */
    internal fun takesAsFallback(profile: ProfileItem): Boolean =
        profile.configType != EConfigType.CUSTOM && profile.configType != EConfigType.POLICYGROUP

    /**
     * A group is filled by a filter rather than by named members, so it can catch several Aether
     * profiles while one core serves one of them. The first one stays, together with any whose
     * settings are the same; the others are left out. A chain or a routing rule names its profiles,
     * so a conflict there is reported instead.
     */
    internal fun withOneAetherProfile(members: List<ProfileItem>): Pair<List<ProfileItem>, List<ProfileItem>> {
        var kept: AetherCore? = null
        return members.partition { member ->
            if (member.configType != EConfigType.AETHER) return@partition true
            val core = AetherCore.of(member)
            when (kept) {
                null -> {
                    kept = core
                    true
                }

                core -> true
                else -> false
            }
        }
    }

    /**
     * A member the core can dial. An Aether profile has no address of its own to check: its core
     * finds the endpoint, and the outbound built for it points at that core.
     */
    private fun ProfileItem.hasDialableServer(): Boolean =
        configType == EConfigType.AETHER ||
            (server.isNotNullEmpty() && (Utils.isPureIpAddress(server!!) || Utils.isValidUrl(server!!)))
}
