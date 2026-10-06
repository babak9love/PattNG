package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherExitNode
import com.v2ray.ang.ui.compose.FormDropdownField

/**
 * The exit-node an Aether core dials out through, in the Aether editor and on the WARP keys page alike: freedom, the
 * default, with the finalMask and the dialMode set beside it, or one of [nodes], profiles a proxy chain takes for a hop,
 * whose outbound then is the exit-node. [value] is the guid of the chosen profile, blank for freedom; a guid none of
 * [nodes] has, a profile that is gone, shows as such until another is chosen. [nodes] is null until they are read.
 */
@Composable
internal fun ExitNodeField(
    value: String,
    nodes: List<AetherExitNode>?,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    val freedom = stringResource(R.string.aether_exit_node_freedom)
    val missing = stringResource(R.string.aether_exit_node_missing)
    val known = nodes.orEmpty()
    val labels = remember(known, freedom, missing) { exitNodeLabels(known, setOf(freedom, missing)) }
    val chosen = known.indexOfFirst { it.guid == value }
    FormDropdownField(
        label = stringResource(R.string.aether_lab_exit_node),
        value = when {
            value.isBlank() -> freedom
            chosen >= 0 -> labels[chosen]
            // Until the profiles are read, a chosen one is not called missing.
            nodes == null -> ""
            else -> missing
        },
        options = listOf(freedom) + labels,
        onValueChange = { picked ->
            if (picked == freedom) {
                onValueChange("")
            } else {
                labels.indexOf(picked).takeIf { it >= 0 }?.let { onValueChange(known[it].guid) }
            }
        },
        enabled = enabled,
        supportingText = if (value.isNotBlank()) stringResource(R.string.aether_hint_exit_node) else null,
    )
}
