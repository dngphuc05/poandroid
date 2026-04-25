package com.pocketmocap.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.pocketmocap.app.ui.theme.Cloud
import com.pocketmocap.app.ui.theme.MintBright
import com.pocketmocap.app.ui.theme.Slate

@Composable
fun Header(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Cloud.copy(alpha = 0.92f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PocketMocapGlyph()
                Text(
                    text = "Pocket Mocap",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFDfe3E6)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "UP",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate,
                )
            }
        }
    }
}

/** Figma logo — concentric tracking arcs with pointer (viewBox 27.5 × 26.75). */
private const val LOGO_SVG = "M0 13.75C0 11.8333 0.359375 10.0417 1.07812 8.375C1.79688 6.70833 2.78125 5.25521 4.03125 4.01562C5.28125 2.77604 6.73958 1.79688 8.40625 1.07812C10.0729 0.359375 11.8542 0 13.75 0C15.6458 0 17.4271 0.359375 19.0938 1.07812C20.7604 1.79688 22.2188 2.77604 23.4688 4.01562C24.7188 5.25521 25.7031 6.70833 26.4219 8.375C27.1406 10.0417 27.5 11.8333 27.5 13.75H25C25 12.1875 24.7031 10.724 24.1094 9.35938C23.5156 7.99479 22.7083 6.80208 21.6875 5.78125C20.6667 4.76042 19.474 3.95833 18.1094 3.375C16.7448 2.79167 15.2917 2.5 13.75 2.5C12.2083 2.5 10.7552 2.79167 9.39062 3.375C8.02604 3.95833 6.83333 4.76042 5.8125 5.78125C4.79167 6.80208 3.98438 7.99479 3.39062 9.35938C2.79688 10.724 2.5 12.1875 2.5 13.75H0M5 13.75C5 11.2917 5.85417 9.21875 7.5625 7.53125C9.27083 5.84375 11.3333 5 13.75 5C16.1667 5 18.2292 5.84375 19.9375 7.53125C21.6458 9.21875 22.5 11.2917 22.5 13.75H20C20 12.0208 19.3906 10.5469 18.1719 9.32812C16.9531 8.10938 15.4792 7.5 13.75 7.5C12.0208 7.5 10.5469 8.10938 9.32812 9.32812C8.10938 10.5469 7.5 12.0208 7.5 13.75H5M10 26.75L8.25 25L12.5 20.75V16.625C11.9375 16.375 11.4844 15.9896 11.1406 15.4688C10.7969 14.9479 10.625 14.375 10.625 13.75C10.625 12.875 10.9271 12.1354 11.5312 11.5312C12.1354 10.9271 12.875 10.625 13.75 10.625C14.625 10.625 15.3646 10.9271 15.9688 11.5312C16.5729 12.1354 16.875 12.875 16.875 13.75C16.875 14.375 16.7031 14.9479 16.3594 15.4688C16.0156 15.9896 15.5625 16.375 15 16.625V20.75L19.25 25L17.5 26.75L13.75 23L10 26.75"

@Composable
private fun PocketMocapGlyph() {
    val path = remember { PathParser().parsePathString(LOGO_SVG).toPath() }
    Canvas(modifier = Modifier.size(width = 28.dp, height = 27.dp)) {
        scale(size.width / 27.5f, size.height / 26.75f, pivot = Offset.Zero) {
            drawPath(path, color = MintBright)
        }
    }
}
