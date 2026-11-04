package com.lingyue.layerbridge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 卡片内边距 统一来源 */
internal object CardMetrics {
    val InnerPadding = 12.dp
    val InfoPadding = 12.dp
    val ActionHeight = 62.dp
    val ButtonHeight = 44.dp
    val ButtonGap = 10.dp
}

/** 左对齐的键值行 主页与详情共用 */
@Composable
internal fun InfoRow(label: String, value: String, spread: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(value, style = MiuixTheme.textStyles.body2)
    }
}

/** 主页分区卡片 标题加内容 */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(CardMetrics.InnerPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MiuixTheme.textStyles.title3)
            content()
        }
    }
}

/** 三横杠菜单图标 24dp 圆角线 */
@Composable
internal fun MenuIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = size.height / 12f
        val radius = stroke
        val left = size.width * 0.15f
        val right = size.width * 0.85f
        listOf(0.3f, 0.5f, 0.7f).forEach { y ->
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(left, size.height * y - stroke / 2),
                size = androidx.compose.ui.geometry.Size(right - left, stroke),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )
        }
    }
}

/** 卡片底部等尺寸操作按钮 */
@Composable
internal fun CardActionButton(
    text: String,
    onClick: () -> Unit,
    width: Dp,
    height: Dp = CardMetrics.ButtonHeight,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.width(width).height(height),
        minWidth = 0.dp,
        minHeight = 0.dp,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    )
}
