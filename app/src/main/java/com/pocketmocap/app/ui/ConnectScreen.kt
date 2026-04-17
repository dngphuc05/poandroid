package com.pocketmocap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketmocap.app.PocketMocapViewModel.ConnectionState
import com.pocketmocap.app.PocketMocapViewModel.UiState
import com.pocketmocap.app.ui.theme.Cloud
import com.pocketmocap.app.ui.theme.CloudWarm
import com.pocketmocap.app.ui.theme.Glass
import com.pocketmocap.app.ui.theme.Ink
import com.pocketmocap.app.ui.theme.Mint
import com.pocketmocap.app.ui.theme.MintBright
import com.pocketmocap.app.ui.theme.MintDeep
import com.pocketmocap.app.ui.theme.RoseMist
import com.pocketmocap.app.ui.theme.Slate

@Composable
fun ConnectScreen(
    uiState: UiState,
    onConnect: (String) -> Unit,
    onClearError: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(uiState.serverUrl) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CloudWarm),
    ) {
        // Ambient blur circles
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(468.dp)
                .blur(56.dp)
                .background(Mint.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 100.dp)
                .size(260.dp)
                .blur(44.dp)
                .background(RoseMist.copy(alpha = 0.10f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Logo glyph
            PocketMocapLogo()

            Spacer(modifier = Modifier.height(16.dp))

            // App name
            Text(
                text = "Pocket Mocap",
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "One Tap to Mo-cap",
                style = MaterialTheme.typography.bodyLarge,
                color = Slate,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Server URL card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Glass,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "SERVER CONNECTION",
                        style = MaterialTheme.typography.labelMedium,
                        color = Slate,
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MintDeep,
                            unfocusedBorderColor = Slate.copy(alpha = 0.2f),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                        ),
                    )

                    // Connect button
                    ConnectButton(
                        connectionState = uiState.connectionState,
                        onClick = { onConnect(serverUrl) },
                    )

                    // Status text
                    when (uiState.connectionState) {
                        ConnectionState.CONNECTED -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Mint)
                                )
                                Text(
                                    text = "Connected · ${uiState.sessionId.take(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MintDeep,
                                )
                            }
                        }
                        ConnectionState.CONNECTING -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MintDeep,
                                )
                                Text(
                                    text = "Connecting...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate,
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }

            // Error message
            uiState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFFF0F0),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB00020),
                        modifier = Modifier
                            .padding(16.dp)
                            .clickable { onClearError() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectButton(
    connectionState: ConnectionState,
    onClick: () -> Unit,
) {
    val isConnecting = connectionState == ConnectionState.CONNECTING
    val isConnected = connectionState == ConnectionState.CONNECTED

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(if (isConnected) 0.dp else 12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    isConnected -> Brush.linearGradient(listOf(Mint.copy(alpha = 0.2f), Mint.copy(alpha = 0.2f)))
                    else -> Brush.linearGradient(listOf(MintDeep, MintDeep.copy(alpha = 0.85f)))
                }
            )
            .clickable(enabled = !isConnecting && !isConnected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.Check else Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = if (isConnected) MintDeep else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = when {
                    isConnected -> "Connected"
                    isConnecting -> "Connecting..."
                    else -> "Connect to Server"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (isConnected) MintDeep else Color.White,
            )
        }
    }
}

