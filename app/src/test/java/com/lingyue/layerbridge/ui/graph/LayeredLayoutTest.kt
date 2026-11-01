package com.lingyue.layerbridge.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredLayoutTest {

    private fun node(id: String) = GraphNodeInput(id, width = 100f, height = 60f)

    @Test
    fun chainKeepsRanksIncreasing() {
        val result = LayeredLayout.layout(
            nodes = listOf(node("a"), node("b"), node("c")),
            edges = listOf(GraphEdgeInput("a", "b"), GraphEdgeInput("b", "c")),
        )
        val a = result.nodes.first { it.id == "a" }
        val b = result.nodes.first { it.id == "b" }
        val c = result.nodes.first { it.id == "c" }
        assertTrue(a.x < b.x)
        assertTrue(b.x < c.x)
        assertEquals(2, result.edges.size)
        assertTrue(result.nodes.isNotEmpty())
    }

    @Test
    fun longEdgeGetsDummyNode() {
        val result = LayeredLayout.layout(
            nodes = listOf(node("a"), node("b"), node("c"), node("d")),
            edges = listOf(
                GraphEdgeInput("a", "b"),
                GraphEdgeInput("b", "c"),
                GraphEdgeInput("c", "d"),
                GraphEdgeInput("a", "d"),
            ),
        )
        assertTrue(result.nodes.none { it.id.startsWith("\u0000") })
        assertTrue(result.nodes.any { it.id == "d" })
        assertTrue(result.edges.all { it.points.size == 2 })
        assertTrue(result.width > 0f)
    }

    @Test
    fun cycleDoesNotHang() {
        val result = LayeredLayout.layout(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(GraphEdgeInput("a", "b"), GraphEdgeInput("b", "a")),
        )
        assertEquals(2, result.nodes.size)
        assertTrue(result.edges.isNotEmpty())
    }

    @Test
    fun siblingsDoNotOverlapVertically() {
        val result = LayeredLayout.layout(
            nodes = listOf(node("root"), node("x"), node("y")),
            edges = listOf(GraphEdgeInput("root", "x"), GraphEdgeInput("root", "y")),
        )
        val x = result.nodes.first { it.id == "x" }
        val y = result.nodes.first { it.id == "y" }
        assertTrue(Math.abs(x.y - y.y) >= 60f)
    }
}
