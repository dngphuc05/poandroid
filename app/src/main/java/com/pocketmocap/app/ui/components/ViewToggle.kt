package com.pocketmocap.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketmocap.app.ui.theme.Mint
import com.pocketmocap.app.ui.theme.MintDeep
import com.pocketmocap.app.ui.theme.PocketMocapMotion
import com.pocketmocap.app.ui.theme.Slate

enum class CaptureView(val label: String) {
    AVATAR("Avatar"),
    SKELETON("Skeleton"),
    TECHNICAL("Technical"),
}

@Composable
fun ViewToggle(
    activeView: CaptureView,
    onViewSelected: (CaptureView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFFE4E8EB).copy(alpha = 0.82f),
        shadowElevation = 16.dp,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CaptureView.entries.forEach { view ->
                SegmentedAction(
                    label = view.label,
