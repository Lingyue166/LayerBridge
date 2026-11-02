package com.lingyue.layerbridge.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.ui.graph.GraphEdgeInput
import com.lingyue.layerbridge.ui.graph.GraphNodeInput
import com.lingyue.layerbridge.ui.graph.LayeredLayout
import com.lingyue.layerbridge.ui.graph.PlacedEdge
import com.lingyue.layerbridge.ui.graph.PlacedNode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.min

private const val PAD_DP = 24f

@Composable
internal fun DependencyGraph(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val metrics = rememberPackageCardMetrics()
    val density = LocalDensity.current
    val installed = remember(state.installed) { state.installed.sortedBy { it.name } }
    val layout = remember(installed, metrics.cardWidth, metrics.cardHeight) {
        buildLayout(installed, metrics)
    }
    val byName = remember(installed) { installed.associateBy { it.name } }
    var selected by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<InstalledPackage?>(null) }
    var pendingRemove by remember { mutableStateOf<InstalledPackage?>(null) }

    val highlightSet = remember(layout.edges, layout.nodes, selected) {
        val active = selected ?: return@remember emptySet()
        val nodeOf = { edge: PlacedEdge, start: Boolean ->
            val point = if (start) edge.points.first() else edge.points.last()
            layout.nodes.firstOrNull {
                if (start) it.x + it.width == point.first && it.y + it.height / 2 == point.second
                else it.x == point.first && it.y + it.height / 2 == point.second
            }?.id
        }
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        layout.edges.forEach { edge ->
            val from = nodeOf(edge, true)
            val to = nodeOf(edge, false)
            if (from != null && to != null) {
                adjacency.getOrPut(from) { mutableSetOf() } += to
                adjacency.getOrPut(to) { mutableSetOf() } += from
            }
        }
        val result = mutableSetOf(active)
        val queue = ArrayDeque(listOf(active))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            adjacency[node].orEmpty().forEach { next ->
                if (result.add(next)) queue += next
            }
        }
        result
    }

    // 动画期间显示的树 切到 nullptr 时保留它淡出 结束后再清空
    var shownSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    val highlightProgress = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected != null) {
            shownSet = highlightSet
            if (highlightProgress.value > 0f) highlightProgress.snapTo(0f)
            highlightProgress.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
        } else if (shownSet.isNotEmpty()) {
            highlightProgress.animateTo(0f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
            shownSet = emptySet()
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var fitted by remember { mutableStateOf(false) }

    val contentW = with(density) { (layout.width + PAD_DP * 2).dp.toPx() }
    val contentH = with(density) { (layout.height + PAD_DP * 2).dp.toPx() }

    fun fit() {
        if (viewport.width == 0 || viewport.height == 0) return
        val newScale = min(1f, viewport.width / contentW * 0.96f).coerceAtLeast(0.12f)
        scale = newScale
        offset = Offset((viewport.width - contentW * newScale) / 2f, 0f)
    }

    LaunchedEffect(layout, viewport) {
        if (!fitted && viewport != IntSize.Zero) {
            fit()
            fitted = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(layout) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(0.15f, 2.5f)
                    val factor = newScale / oldScale
                    offset = (offset - centroid) * factor + centroid + pan
                    scale = newScale
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(layout) {
                    detectTapGestures(
                        onTap = {
                            viewModel.setQuery("")
                            selected = null
                        },
                        onDoubleTap = { selected = null },
                    )
                },
        )
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
                .size(
                    (layout.width + PAD_DP * 2).dp,
                    (layout.height + PAD_DP * 2).dp,
                ),
        ) {
            val stroke = with(density) { 2.dp.toPx() }
            val lineColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
            val dotColor = MiuixTheme.colorScheme.primary
            val highlightColor = MiuixTheme.colorScheme.primary
            Canvas(Modifier.size((layout.width + PAD_DP * 2).dp, (layout.height + PAD_DP * 2).dp)) {
                layout.edges.forEach { edge ->
                    val start = edge.points.first()
                    val end = edge.points.last()
                    val startX = with(density) { (start.first + PAD_DP).dp.toPx() }
                    val startY = with(density) { (start.second + PAD_DP).dp.toPx() }
                    val endX = with(density) { (end.first + PAD_DP).dp.toPx() }
                    val endY = with(density) { (end.second + PAD_DP).dp.toPx() }
                    val inTree = layout.nodes.any {
                        it.x + it.width == start.first &&
                            it.y + it.height / 2 == start.second &&
                            it.id in shownSet
                    } && layout.nodes.any {
                        it.x == end.first &&
                            it.y + it.height / 2 == end.second &&
                            it.id in shownSet
                    }
                    val dimAlpha = 1f - 0.85f * highlightProgress.value
                    val color = if (inTree) lineColor else lineColor.copy(alpha = dimAlpha)
                    val width = if (inTree) stroke * (1f + 0.6f * highlightProgress.value) else stroke
                    val control = (endX - startX) / 2f
                    val path = Path()
                    path.moveTo(startX, startY)
                    path.cubicTo(startX + control, startY, endX - control, endY, endX, endY)
                    drawPath(path, color = color, style = Stroke(width = width))
                    val arrowSize = with(density) { 7.dp.toPx() }
                    val arrow = Path().apply {
                        moveTo(startX, startY)
                        lineTo(startX + arrowSize, startY - arrowSize * 0.6f)
                        lineTo(startX + arrowSize, startY + arrowSize * 0.6f)
                        close()
                    }
                    drawPath(arrow, color = color)
                }
            }
            layout.nodes.forEach { node ->
                val pkg = byName[node.id] ?: return@forEach
                Box(
                    Modifier
                        .offset((node.x + PAD_DP).dp, (node.y + PAD_DP).dp)
                        .size(metrics.cardWidth, metrics.cardHeight),
                ) {
                    PackageCard(
                        pkg = pkg,
                        metrics = metrics,
                        query = state.query,
                        onClick = { detail = pkg },
                        onRemove = { pendingRemove = pkg },
                        highlight = when {
                            shownSet.isEmpty() -> CardHighlight.None
                            node.id in shownSet -> CardHighlight.Active
                            else -> CardHighlight.Dimmed
                        },
                        highlightProgress = { highlightProgress.value },
                        onDoubleTap = { selected = if (selected == node.id) null else node.id },
                    )
                }
            }
            Canvas(Modifier.size((layout.width + PAD_DP * 2).dp, (layout.height + PAD_DP * 2).dp)) {
                val radius = with(density) { 4.dp.toPx() }
                layout.edges.forEach { edge ->
                    listOf(edge.points.first(), edge.points.last()).forEach { point ->
                        drawCircle(
                            color = dotColor,
                            radius = radius,
                            center = Offset(
                                with(density) { (point.first + PAD_DP).dp.toPx() },
                                with(density) { (point.second + PAD_DP).dp.toPx() },
                            ),
                        )
                    }
                }
            }
        }
    }

    PackageDialogs(detail, pendingRemove, viewModel) {
        detail = null
        pendingRemove = null
    }
}

private data class RenderLayout(
    val nodes: List<PlacedNode>,
    val edges: List<PlacedEdge>,
    val width: Float,
    val height: Float,
)

private fun buildLayout(
    installed: List<InstalledPackage>,
    metrics: PackageCardMetrics,
): RenderLayout {
    val names = installed.map { it.name }.toSet()
    val edges = installed.flatMap { pkg ->
        parseDependencyNames(pkg.relations.depends)
            .filter { name -> name in names && name != pkg.name }
            .distinct()
            .map { GraphEdgeInput(pkg.name, it) }
    }
    val connectedNames = edges.flatMap { listOf(it.from, it.to) }.toSet()
    val connected = installed.filter { it.name in connectedNames }
    val isolated = installed.filter { it.name !in connectedNames }
    val graph = LayeredLayout.layout(
        nodes = connected.map { GraphNodeInput(it.name, metrics.cardWidth.value, metrics.cardHeight.value) },
        edges = edges,
    )
    val colGap = 20f
    val rowGap = 20f
    val columns = if (isolated.isEmpty()) 1 else {
        val fitting = ((graph.width + colGap) / (metrics.cardWidth.value + colGap)).toInt().coerceIn(1, 4)
        minOf(columnsFor(isolated.size), fitting).coerceAtLeast(1)
    }
    val extra = isolated.mapIndexed { index, pkg ->
        val row = index / columns
        val col = index % columns
        PlacedNode(
            id = pkg.name,
            x = col * (metrics.cardWidth.value + colGap),
            y = graph.height + 24f + row * (metrics.cardHeight.value + rowGap),
            width = metrics.cardWidth.value,
            height = metrics.cardHeight.value,
        )
    }
    val rows = if (isolated.isEmpty()) 0 else (isolated.size + columns - 1) / columns
    val gridWidth = if (isolated.isEmpty()) 0f else {
        minOf(columns, isolated.size) * (metrics.cardWidth.value + colGap) - colGap
    }
    return RenderLayout(
        nodes = graph.nodes + extra,
        edges = graph.edges,
        width = maxOf(graph.width, gridWidth),
        height = if (isolated.isEmpty()) graph.height else {
            graph.height + 24f + rows * (metrics.cardHeight.value + rowGap) - rowGap
        },
    )
}

private fun columnsFor(count: Int): Int {
    if (count <= 3) return count.coerceAtLeast(1)
    var best = 1
    var bestAspect = Float.MAX_VALUE
    for (columns in 1..count) {
        val rows = (count + columns - 1) / columns
        val diff = kotlin.math.abs(columns.toFloat() / rows - 1f)
        if (diff < bestAspect) {
            bestAspect = diff
            best = columns
        }
    }
    return best
}

/** 依赖名提取：忽略版本号与架构限定 */
internal fun parseDependencyNames(depends: String?): List<String> {
    if (depends.isNullOrBlank()) return emptyList()
    return depends.split(',')
        .map { part ->
            part.trim()
                .substringBefore(' ')
                .substringBefore('(')
                .substringBefore(':')
                .substringBefore('|')
        }
        .filter { it.isNotBlank() }
}
