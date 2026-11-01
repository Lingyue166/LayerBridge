package com.lingyue.layerbridge.ui

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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.min

private const val PAD_DP = 24f

@Composable
internal fun DependencyGraph(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val metrics = rememberPackageCardMetrics()
    val density = LocalDensity.current
    val installed = remember(state.installed) { state.installed.sortedBy { it.name } }
    val layout = remember(installed, metrics.cardWidth, metrics.cardHeight) {
        val nodes = installed.map {
            GraphNodeInput(it.name, metrics.cardWidth.value, metrics.cardHeight.value)
        }
        val edges = installed.flatMap { pkg ->
            parseDependencyNames(pkg.relations.depends)
                .filter { name -> installed.any { it.name == name } && name != pkg.name }
                .distinct()
                .map { GraphEdgeInput(pkg.name, it) }
        }
        LayeredLayout.layout(nodes, edges)
    }
    val byName = remember(installed) { installed.associateBy { it.name } }
    var detail by remember { mutableStateOf<InstalledPackage?>(null) }
    var pendingRemove by remember { mutableStateOf<InstalledPackage?>(null) }

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
        offset = Offset(
            (viewport.width - contentW * newScale) / 2f,
            0f,
        )
    }

    LaunchedEffect(layout, viewport) {
        if (!fitted && viewport != IntSize.Zero) {
            fit()
            fitted = true
        }
    }

    val minScale = 0.15f
    val maxScale = 2.5f

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(layout) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)
                    val factor = newScale / oldScale
                    // 以双指中心为锚点缩放 再叠加拖动
                    offset = (offset - centroid) * factor + centroid + pan
                    scale = newScale
                }
            }
            .pointerInput(layout) {
                detectTapGestures(onDoubleTap = { fit() })
            },
    ) {
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
            Canvas(Modifier.size((layout.width + PAD_DP * 2).dp, (layout.height + PAD_DP * 2).dp)) {
                layout.edges.forEach { edge ->
                    val points = edge.points
                    if (points.size < 2) return@forEach
                    val start = points.first()
                    val end = points.last()
                    val startX = with(density) { (start.first + PAD_DP).dp.toPx() }
                    val startY = with(density) { (start.second + PAD_DP).dp.toPx() }
                    val endX = with(density) { (end.first + PAD_DP).dp.toPx() }
                    val endY = with(density) { (end.second + PAD_DP).dp.toPx() }
                    val control = (endX - startX) / 2f
                    val path = Path()
                    path.moveTo(startX, startY)
                    path.cubicTo(startX + control, startY, endX - control, endY, endX, endY)
                    drawPath(path, color = lineColor, style = Stroke(width = stroke))
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
                    )
                }
            }
        }
    }

    PackageDialogs(detail, pendingRemove, viewModel) {
        detail = null
        pendingRemove = null
    }
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
