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

@Composable
private fun PocketMocapLogo() {
    val transition = rememberInfiniteTransition(label = "logo")
    val pulse by transition.animateFloat(
        initialValue = 0.90f, targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "logoPulse",
    )
    val path = remember {
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M0 13.75C0 11.8333 0.359375 10.0417 1.07812 8.375C1.79688 6.70833 2.78125 5.25521 4.03125 4.01562C5.28125 2.77604 6.73958 1.79688 8.40625 1.07812C10.0729 0.359375 11.8542 0 13.75 0C15.6458 0 17.4271 0.359375 19.0938 1.07812C20.7604 1.79688 22.2188 2.77604 23.4688 4.01562C24.7188 5.25521 25.7031 6.70833 26.4219 8.375C27.1406 10.0417 27.5 11.8333 27.5 13.75H25C25 12.1875 24.7031 10.724 24.1094 9.35938C23.5156 7.99479 22.7083 6.80208 21.6875 5.78125C20.6667 4.76042 19.474 3.95833 18.1094 3.375C16.7448 2.79167 15.2917 2.5 13.75 2.5C12.2083 2.5 10.7552 2.79167 9.39062 3.375C8.02604 3.95833 6.83333 4.76042 5.8125 5.78125C4.79167 6.80208 3.98438 7.99479 3.39062 9.35938C2.79688 10.724 2.5 12.1875 2.5 13.75H0M5 13.75C5 11.2917 5.85417 9.21875 7.5625 7.53125C9.27083 5.84375 11.3333 5 13.75 5C16.1667 5 18.2292 5.84375 19.9375 7.53125C21.6458 9.21875 22.5 11.2917 22.5 13.75H20C20 12.0208 19.3906 10.5469 18.1719 9.32812C16.9531 8.10938 15.4792 7.5 13.75 7.5C12.0208 7.5 10.5469 8.10938 9.32812 9.32812C8.10938 10.5469 7.5 12.0208 7.5 13.75H5M10 26.75L8.25 25L12.5 20.75V16.625C11.9375 16.375 11.4844 15.9896 11.1406 15.4688C10.7969 14.9479 10.625 14.375 10.625 13.75C10.625 12.875 10.9271 12.1354 11.5312 11.5312C12.1354 10.9271 12.875 10.625 13.75 10.625C14.625 10.625 15.3646 10.9271 15.9688 11.5312C16.5729 12.1354 16.875 12.875 16.875 13.75C16.875 14.375 16.7031 14.9479 16.3594 15.4688C16.0156 15.9896 15.5625 16.375 15 16.625V20.75L19.25 25L17.5 26.75L13.75 23L10 26.75"
        ).toPath()
    }

    Box(contentAlignment = Alignment.Center) {
        // Outer glow halo
        Canvas(modifier = Modifier.size(120.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Mint.copy(alpha = 0.12f), Color.Transparent)
                ),
                radius = size.minDimension * 0.5f * pulse,
            )
        }
        // Figma logo — concentric tracking arcs with pointer
        Canvas(modifier = Modifier.size(80.dp)) {
            val sx = size.width / 27.5f
            val sy = size.height / 26.75f
            scale(sx, sy, pivot = Offset.Zero) {
                drawPath(path, color = MintBright)
            }
        }
    }
}
