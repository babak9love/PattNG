package com.v2ray.ang.ui.server

import com.v2ray.ang.core.AetherExitNode
import com.v2ray.ang.core.ExitNodeOutbound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExitNodesTest {

    @Test
    fun eachLabelIsTheNameMadeUniqueWhereItIsTakenAlready() {
        assertEquals(
            listOf("germany", "freedom (default) (2)", "france", "freedom (default) (2) (2)"),
            exitNodeLabels(listOf("germany", "freedom (default)", "france", "freedom (default) (2)"), setOf("freedom (default)")),
        )
        assertEquals(emptyList<String>(), exitNodeLabels(emptyList(), setOf("freedom (default)")))
    }

    @Test
    fun aNameNoProfileHasAnyMoreOrSeveralHaveIsTold() {
        val nodes = listOf(AetherExitNode("germany", 1), AetherExitNode("france", 2))
        assertNull(problemOfExitNode("germany", nodes))
        assertNull(problemOfExitNode(" germany ", nodes))
        assertEquals(ExitNodeOutbound.SameName, problemOfExitNode("france", nodes))
        assertEquals(ExitNodeOutbound.NotFound, problemOfExitNode("spain", nodes))
        assertEquals(ExitNodeOutbound.NotFound, problemOfExitNode("germany", emptyList()))
        // Freedom is no name, and nothing is told before the names are read.
        assertNull(problemOfExitNode("", nodes))
        assertNull(problemOfExitNode("  ", nodes))
        assertNull(problemOfExitNode("spain", null))
    }
}
