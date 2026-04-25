package com.pocketmocap.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketmocap.app.ui.theme.Cloud
import com.pocketmocap.app.ui.theme.Ink
import com.pocketmocap.app.ui.theme.Mint
import com.pocketmocap.app.ui.theme.MintBright
import com.pocketmocap.app.ui.theme.PocketMocapMotion

enum class NavTab { SETUP, CAPTURE, LIBRARY }

@Composable
fun BottomNav(
    activeTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp)),
        color = Cloud.copy(alpha = 0.8f),
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 27.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BottomNavItem(
                icon = Icons.Rounded.Adjust,
                label = "SETUP",
                selected = activeTab == NavTab.SETUP,
                onClick = { onTabSelected(NavTab.SETUP) },
            )
            BottomNavItem(
                icon = Icons.Rounded.Videocam,
                label = "CAPTURE",
                selected = activeTab == NavTab.CAPTURE,
                onClick = { onTabSelected(NavTab.CAPTURE) },
            )
            BottomNavItem(
                icon = Icons.Rounded.CollectionsBookmark,
                label = "LIBRARY",
                selected = activeTab == NavTab.LIBRARY,
                onClick = { onTabSelected(NavTab.LIBRARY) },
            )
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val iconColor by animateColorAsState(
