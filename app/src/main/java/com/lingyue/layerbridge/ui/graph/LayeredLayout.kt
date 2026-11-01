package com.lingyue.layerbridge.ui.graph

/**
 * 分层布局（Sugiyama / dagre 思路）：
 * 去环 -> 最长路径分层 -> 长边插虚拟节点 -> 重心排序减少交叉 ->
 * 按层间连线数量动态加宽间距并为每条线分配独立通道 -> 层内坐标按邻居中位数对齐。
 * 纯函数 便于单元测试。
 */

data class GraphNodeInput(
    val id: String,
    val width: Float = 100f,
    val height: Float = 60f,
)

data class GraphEdgeInput(val from: String, val to: String)

data class PlacedNode(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class PlacedEdge(val points: List<Pair<Float, Float>>)

data class LayeredLayoutResult(
    val nodes: List<PlacedNode>,
    val edges: List<PlacedEdge>,
    val width: Float,
    val height: Float,
)

object LayeredLayout {

    fun layout(
        nodes: List<GraphNodeInput>,
        edges: List<GraphEdgeInput>,
        layerGap: Float = 96f,
        nodeGap: Float = 28f,
        channelGap: Float = 16f,
    ): LayeredLayoutResult {
        if (nodes.isEmpty()) return LayeredLayoutResult(emptyList(), emptyList(), 0f, 0f)
        val ids = nodes.map { it.id }.toSet()
        val clean = edges
            .filter { it.from in ids && it.to in ids && it.from != it.to }
            .distinct()

        val acyclic = removeCycles(nodes.map { it.id }, clean)
        val ranks = assignRanks(nodes.map { it.id }, acyclic)

        // 每条原始边拆成相邻层之间的链 记录经过的节点（含虚拟节点）
        val chains = mutableListOf<List<String>>()
        val segEdges = mutableListOf<GraphEdgeInput>()
        var dummySeq = 0
        acyclic.forEach { edge ->
            val fromRank = ranks[edge.from] ?: 0
            val toRank = ranks[edge.to] ?: 0
            val chain = mutableListOf(edge.from)
            for (rank in fromRank + 1 until toRank) {
                val dummyId = "\u0000d${dummySeq++}"
                ranks[dummyId] = rank
                chain += dummyId
            }
            chain += edge.to
            chains += chain
            chain.zipWithNext { a, b -> segEdges += GraphEdgeInput(a, b) }
        }

        val allIds = nodes.map { it.id } + ranks.keys.filter { it.startsWith("\u0000d") }
        val layerCount = (ranks.values.maxOrNull() ?: 0) + 1
        val layers = MutableList(layerCount) { mutableListOf<String>() }
        allIds.sorted().forEach { id -> layers[ranks[id] ?: 0] += id }

        orderLayers(layers, segEdges)

        val widthByLayer = FloatArray(layerCount)
        layers.forEachIndexed { layer, layerIds ->
            widthByLayer[layer] = layerIds.maxOfOrNull { id ->
                nodes.firstOrNull { it.id == id }?.width ?: 4f
            } ?: 4f
        }
        val heightById = allIds.associateWith { id ->
            nodes.firstOrNull { it.id == id }?.height ?: 4f
        }

        // 每条原始边经过的间隙顺序
        val edgeSpans = chains.map { chain ->
            (0 until chain.size - 1).map { i -> ranks.getValue(chain[i]) }
        }
        val gapCount = IntArray((layerCount - 1).coerceAtLeast(0))
        edgeSpans.forEach { spans ->
            spans.forEach { gap -> if (gap in gapCount.indices) gapCount[gap]++ }
        }
        val gapWidth = FloatArray(gapCount.size) { g ->
            layerGap + (gapCount[g] - 1).coerceAtLeast(0) * channelGap
        }
        val xOf = FloatArray(layerCount)
        for (g in 0 until layerCount - 1) {
            xOf[g + 1] = xOf[g] + widthByLayer[g] + gapWidth[g]
        }

        val yOf = mutableMapOf<String, Float>()
        layers.forEach { layerIds ->
            var y = 0f
            layerIds.forEach { id ->
                yOf[id] = y
                y += heightById.getValue(id) + nodeGap
            }
        }

        val preds = mutableMapOf<String, MutableList<String>>()
        val succs = mutableMapOf<String, MutableList<String>>()
        segEdges.forEach { edge ->
            succs.getOrPut(edge.from) { mutableListOf() } += edge.to
            preds.getOrPut(edge.to) { mutableListOf() } += edge.from
        }
        repeat(12) {
            layers.forEach { layerIds ->
                if (layerIds.isEmpty()) return@forEach
                val desired = layerIds.associateWith { id ->
                    val neighbors = (preds[id].orEmpty() + succs[id].orEmpty())
                        .mapNotNull { yOf[it]?.plus(heightById.getValue(it) / 2) }
                    if (neighbors.isEmpty()) yOf.getValue(id) + heightById.getValue(id) / 2
                    else neighbors.sorted()[neighbors.size / 2]
                }
                var y = 0f
                layerIds.sortedBy { desired[it] }.forEach { id ->
                    yOf[id] = y
                    y += heightById.getValue(id) + nodeGap
                }
            }
        }

        // 通道分配：同一间隙内按线的 y 排序平均分布 间距随线数增大
        val slotX = mutableMapOf<Pair<Int, Int>, Float>()
        val slotY = mutableMapOf<Pair<Int, Int>, Float>()
        val centerById = allIds.associateWith { id -> yOf.getValue(id) + heightById.getValue(id) / 2 }
        edgeSpans.forEachIndexed { edgeIndex, spans ->
            val chain = chains[edgeIndex]
            val fromRank = ranks.getValue(chain.first())
            val toRank = ranks.getValue(chain.last())
            spans.forEach { gap ->
                val progress = ((gap + 1 - fromRank).toFloat()) / (toRank - fromRank).coerceAtLeast(1)
                val y = centerById.getValue(chain.first()) +
                    (centerById.getValue(chain.last()) - centerById.getValue(chain.first())) * progress
                slotX[edgeIndex to gap] = xOf[gap] + widthByLayer[gap] + 0f
                slotY[edgeIndex to gap] = y
            }
        }
        // 每个间隙按 y 排序后重新分配 x
        (0 until gapCount.size).forEach { gap ->
            val entries = edgeSpans.indices
                .filter { gap in edgeSpans[it] }
                .map { it to slotY.getValue(it to gap) }
                .sortedBy { it.second }
            val left = xOf[gap] + widthByLayer[gap]
            val width = gapWidth[gap]
            entries.forEachIndexed { index, (edgeIndex, _) ->
                slotX[edgeIndex to gap] = left + width * (index + 1) / (entries.size + 1)
            }
        }

        val placed = allIds.filter { !it.startsWith("\u0000d") }.mapNotNull { id ->
            val input = nodes.first { it.id == id }
            PlacedNode(
                id = id,
                x = xOf[ranks.getValue(id)] + (widthByLayer[ranks.getValue(id)] - input.width) / 2f,
                y = yOf.getValue(id),
                width = input.width,
                height = input.height,
            )
        }
        val posById = placed.associateBy { it.id }

        val placedEdges = chains.mapIndexedNotNull { edgeIndex, chain ->
            val from = posById[chain.first()] ?: return@mapIndexedNotNull null
            val to = posById[chain.last()] ?: return@mapIndexedNotNull null
            val points = mutableListOf(
                (from.x + from.width) to (from.y + from.height / 2),
            )
            edgeSpans[edgeIndex].forEach { gap ->
                points += slotX.getValue(edgeIndex to gap) to slotY.getValue(edgeIndex to gap)
            }
            points += to.x to (to.y + to.height / 2)
            PlacedEdge(points)
        }

        val contentWidth = (placed.maxOfOrNull { it.x + it.width } ?: 0f)
        val contentHeight = (placed.maxOfOrNull { it.y + it.height } ?: 0f)
        return LayeredLayoutResult(placed, placedEdges, contentWidth, contentHeight)
    }

    private fun removeCycles(order: List<String>, edges: List<GraphEdgeInput>): List<GraphEdgeInput> {
        val adjacency = edges.groupBy { it.from }.mapValues { (_, list) -> list.map { it.to } }
        val state = mutableMapOf<String, Int>()
        val skipped = mutableSetOf<GraphEdgeInput>()
        fun visit(node: String) {
            state[node] = 1
            adjacency[node].orEmpty().forEach { next ->
                when (state[next] ?: 0) {
                    1 -> skipped += GraphEdgeInput(node, next)
                    0 -> visit(next)
                }
            }
            state[node] = 2
        }
        order.forEach { if ((state[it] ?: 0) == 0) visit(it) }
        return edges.filter { it !in skipped }
    }

    private fun assignRanks(order: List<String>, edges: List<GraphEdgeInput>): MutableMap<String, Int> {
        val indegree = order.associateWith { 0 }.toMutableMap()
        val successors = mutableMapOf<String, MutableList<String>>()
        edges.forEach { edge ->
            indegree[edge.to] = (indegree[edge.to] ?: 0) + 1
            successors.getOrPut(edge.from) { mutableListOf() } += edge.to
        }
        val rank = order.associateWith { 0 }.toMutableMap()
        val queue = ArrayDeque(order.filter { (indegree[it] ?: 0) == 0 })
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            successors[node].orEmpty().forEach { next ->
                rank[next] = maxOf(rank.getValue(next), rank.getValue(node) + 1)
                indegree[next] = indegree.getValue(next) - 1
                if (indegree.getValue(next) == 0) queue += next
            }
        }
        return rank
    }

    private fun orderLayers(layers: MutableList<MutableList<String>>, edges: List<GraphEdgeInput>) {
        if (layers.size <= 1) return
        val preds = mutableMapOf<String, MutableList<String>>()
        val succs = mutableMapOf<String, MutableList<String>>()
        edges.forEach { edge ->
            succs.getOrPut(edge.from) { mutableListOf() } += edge.to
            preds.getOrPut(edge.to) { mutableListOf() } += edge.from
        }
        fun indexOf(layer: List<String>): Map<String, Int> = layer.withIndex().associate { it.value to it.index }

        fun crossings(): Int {
            var total = 0
            for (layer in 0 until layers.size - 1) {
                val upper = indexOf(layers[layer])
                val lower = indexOf(layers[layer + 1])
                val between = edges.filter { it.from in upper && it.to in lower }
                    .map { upper.getValue(it.from) to lower.getValue(it.to) }
                    .sortedBy { it.first }
                for (i in between.indices) {
                    for (j in i + 1 until between.size) {
                        if (between[i].second > between[j].second) total++
                    }
                }
            }
            return total
        }

        var best = layers.map { it.toList() }
        var bestCrossings = crossings()

        repeat(4) {
            for (layer in 1 until layers.size) {
                val upper = indexOf(layers[layer - 1])
                layers[layer] = layers[layer].sortedBy { id ->
                    preds[id].orEmpty().mapNotNull { upper[it] }.average().takeIf { !it.isNaN() }
                        ?: Double.MAX_VALUE
                }.toMutableList()
            }
            for (layer in layers.size - 2 downTo 0) {
                val lower = indexOf(layers[layer + 1])
                layers[layer] = layers[layer].sortedBy { id ->
                    succs[id].orEmpty().mapNotNull { lower[it] }.average().takeIf { !it.isNaN() }
                        ?: Double.MAX_VALUE
                }.toMutableList()
            }
            val current = crossings()
            if (current < bestCrossings) {
                bestCrossings = current
                best = layers.map { it.toList() }
            }
        }
        layers.indices.forEach { layers[it] = best[it].toMutableList() }
    }
}
