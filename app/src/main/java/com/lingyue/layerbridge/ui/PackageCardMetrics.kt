package com.lingyue.layerbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingyue.layerbridge.ui.components.CardMetrics

/** 由屏幕宽度推导的卡片尺寸与字号 所有卡片共用 */
internal data class PackageCardMetrics(
    val cardWidth: Dp,
    val cardHeight: Dp,
    val infoHeight: Dp,
    val actionHeight: Dp,
    val buttonWidth: Dp,
    val buttonHeight: Dp,
    val padding: Dp,
    val gap: Dp,
    val margin: Dp,
    val nameSize: TextUnit,
    val metaSize: TextUnit,
)

@Composable
internal fun rememberPackageCardMetrics(): PackageCardMetrics {
    val widthDp = LocalConfiguration.current.screenWidthDp.dp
    val density = LocalDensity.current
    return remember(widthDp, density) {
    val margin = 12.dp
    val gap = if (widthDp < 360.dp) 8.dp else 12.dp
    val cardWidth = (widthDp - margin * 2 - gap) / 2
    val scale = (widthDp.value / 411f).coerceIn(0.9f, 1.0f)
    val nameSp = (16f * scale).sp
    val metaSp = (12f * scale).sp
    val nameLine = with(density) { (nameSp * 1.5f).toDp() }
    val metaLine = with(density) { (metaSp * 1.5f).toDp() }
    val padding = CardMetrics.InfoPadding
    val infoHeight = padding * 2 + nameLine + 4.dp + metaLine
    val actionHeight = CardMetrics.ActionHeight
    val buttonWidth = ((cardWidth - padding * 2 - CardMetrics.ButtonGap) / 2).coerceAtLeast(64.dp)
    PackageCardMetrics(
        cardWidth = cardWidth,
        cardHeight = infoHeight + actionHeight + 1.dp,
        infoHeight = infoHeight,
        actionHeight = actionHeight,
        buttonWidth = buttonWidth,
        buttonHeight = CardMetrics.ButtonHeight,
        padding = padding,
        gap = gap,
        margin = margin,
        nameSize = nameSp,
        metaSize = metaSp,
    )
    }
}
