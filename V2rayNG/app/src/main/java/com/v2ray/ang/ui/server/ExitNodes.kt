package com.v2ray.ang.ui.server

import com.v2ray.ang.core.AetherExitNode

/**
 * The labels a list of exit-nodes shows for [nodes], in their order: each node's name, made unique by a number after it
 * where a name, or one of [reserved], the labels of the list's own entries, came up before. The list hands back the
 * label picked, so no two may be alike.
 */
internal fun exitNodeLabels(nodes: List<AetherExitNode>, reserved: Set<String>): List<String> {
    val taken = reserved.toMutableSet()
    return nodes.map { node ->
        var label = node.name
        var number = 2
        while (label in taken) label = "${node.name} (${number++})"
        taken += label
        label
    }
}
