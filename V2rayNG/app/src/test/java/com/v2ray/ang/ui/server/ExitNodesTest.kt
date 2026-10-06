package com.v2ray.ang.ui.server

import com.v2ray.ang.core.AetherExitNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExitNodesTest {

    @Test
    fun eachLabelIsTheNameMadeUniqueWhereItCameUpBefore() {
        val nodes = listOf(
            AetherExitNode("a", "germany"),
            AetherExitNode("b", "germany"),
            AetherExitNode("c", "germany (2)"),
            AetherExitNode("d", "freedom (default)"),
            AetherExitNode("e", "france"),
        )
        assertEquals(
            listOf("germany", "germany (2)", "germany (2) (2)", "freedom (default) (2)", "france"),
            exitNodeLabels(nodes, setOf("freedom (default)", "Profile not found")),
        )
        assertEquals(emptyList<String>(), exitNodeLabels(emptyList(), setOf("freedom (default)")))
    }
}
