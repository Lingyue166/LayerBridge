package com.lingyue.layerbridge.ui.graph

/**
 * 分层布局 + 正交线路由（Sugiyama / dagre 思路）。
 * 去环 -> 最长路径分层 -> 虚拟节点排序 -> 重心法减少交叉 ->
 * 每个层间隙按目标节点分组分配互不重叠的竖直通道 -> 卡片端口分散 ->
 * 正交折线从卡片右缘端口出发 经通道 连到左缘端口。
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
        layerPadding: Float = 28f,
        channelWidth: Float = 16f,
        nodeGap: Float = 28f,
    ): LayeredLayoutResult {
        if (nodes.isEmpty()) return LayeredLayoutResult(emptyList(), emptyList(), 0f, 0f)
        val nodeById = nodes.associateBy { it.id }
        val ids = nodeById.keys
        val clean = edges.filter { it.from in ids && it.to in ids && it.from != it.to }.distinct()
        val acyclic = removeCycles(nodes.map { it.id }, clean)
        val ranks = assignRanks(nodes.map { it.id }, acyclic)
        val heightById = nodes.associate { it.id to it.height }.toMutableMap()

        // 每条原始边在层间链上的节点序列
        val chains = mutableListOf<List<String>>()
        val segEdges = mutableListOf<GraphEdgeInput>()
        var dummySeq = 0
        acyclic.forEach { edge ->
            val fromRank = ranks.getValue(edge.from)
            val toRank = ranks.getValue(edge.to)
            val chain = mutableListOf(edge.from)
            for (rank in fromRank + 1 until toRank) {
                val dummyId = "\u0000d${dummySeq++}"
                ranks[dummyId] = rank
                chain += dummyId
                heightById[dummyId] = 4f
            }
            chain += edge.to
            chains += chain
            chain.zipWithNext { a, b -> segEdges += GraphEdgeInput(a, b) }
        }

        val dummyIds = ranks.keys.filter { it.startsWith("\u0000d") }
        val allIds = nodes.map { it.id } + dummyIds
        val layerCount = (ranks.values.maxOrNull() ?: 0) + 1
        val layers = MutableList(layerCount) { mutableListOf<String>() }
        allIds.sorted().forEach { id -> layers[ranks.getValue(id)] += id }
        orderLayers(layers, segEdges)

        val widthByLayer = FloatArray(layerCount) { layer ->
            layers[layer].maxOfOrNull { id -> nodeById[id]?.width ?: 4f } ?: 4f
        }

        // 每个层间隙的通道分组：按下一跳目标节点归并 避免线宽随边数爆炸
        val gapGroups = List(layerCount) { mutableListOf<String>() }
        fun gapTargets(chain: List<String>, gap: Int): String =
            chain.firstOrNull { ranks.getValue(it) == gap + 1 } ?: chain.last()

        chains.forEach { chain ->
            val fromRank = ranks.getValue(chain.first())
            val toRank = ranks.getValue(chain.last())
            for (gap in fromRank until toRank) {
                val target = gapTargets(chain, gap)
                if (target !in gapGroups[gap]) gapGroups[gap] += target
            }
        }
        val gapWidth = FloatArray((layerCount - 1).coerceAtLeast(0)) { gap ->
            layerPadding * 2 + gapGroups[gap].size * channelWidth
        }
        val xOf = FloatArray(layerCount)
        for (gap in 0 until layerCount - 1) {
            xOf[gap + 1] = xOf[gap] + widthByLayer[gap] + gapWidth[gap]
        }

        // 层内纵向坐标
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

        val placed = nodes.map { input ->
            val rank = ranks.getValue(input.id)
            PlacedNode(
                id = input.id,
                x = xOf[rank] + (widthByLayer[rank] - input.width) / 2f,
                y = yOf.getValue(input.id),
                width = input.width,
                height = input.height,
            )
        }
        val posById = placed.associateBy { it.id }
        val centerY = posById.mapValues { (_, node) -> node.y + node.height / 2 }

        // 通道 x：同一间隙内按目标节点中心 y 排序 均分
        val laneX = mutableMapOf<Pair<Int, String>, Float>()
        for (gap in 0 until layerCount - 1) {
            val ordered = gapGroups[gap].sortedBy { centerY[it] ?: Float.MAX_VALUE }
            val left = xOf[gap] + widthByLayer[gap] + layerPadding
            ordered.forEachIndexed { index, target ->
                laneX[gap to target] = left + index * channelWidth
            }
        }

        // 每张卡片只有一个连接点：出边在右缘中点 入边在左缘中点
        val outPort = chains.indices.associateWith { index ->
            posById.getValue(chains[index].first()).let { it.y + it.height / 2 }
        }
        val inPort = chains.indices.associateWith { index ->
            posById.getValue(chains[index].last()).let { it.y + it.height / 2 }
        }

        // 直接连线：起点右缘端口 终点左缘端口 画成平滑 S 曲线 只有两个弯
        val placedEdges = chains.mapIndexed { index, chain ->
            val from = posById.getValue(chain.first())
            val to = posById.getValue(chain.last())
            PlacedEdge(
                listOf(
                    (from.x + from.width) to outPort.getValue(index),
                    to.x to inPort.getValue(index),
                ),
            )
        }

        val contentWidth = placed.maxOfOrNull { it.x + it.width } ?: 0f
        val contentHeight = placed.maxOfOrNull { it.y + it.height } ?: 0f
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
