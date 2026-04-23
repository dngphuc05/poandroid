package com.pocketmocap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketmocap.app.PocketMocapViewModel.CalibrationStep
import com.pocketmocap.app.PocketMocapViewModel.UiState
import com.pocketmocap.app.ui.theme.CloudWarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.pocketmocap.app.ui.theme.Glass
import com.pocketmocap.app.ui.theme.Ink
import com.pocketmocap.app.ui.theme.Mint
import com.pocketmocap.app.ui.theme.MintBright
import com.pocketmocap.app.ui.theme.MintDeep
import com.pocketmocap.app.ui.theme.RoseMist
import com.pocketmocap.app.ui.theme.Slate

@Composable
fun SetupScreen(
    uiState: UiState,
    onStartCalibration: () -> Unit,
    onClearError: () -> Unit,
) {
    val isCalibrating = uiState.calibrationStep != CalibrationStep.PENDING &&
        uiState.calibrationStep != CalibrationStep.COMPLETE
    var showVrmWarning by remember { mutableStateOf(false) }

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
                .background(Mint.copy(alpha = 0.18f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 156.dp)
                .size(width = 234.dp, height = 280.dp)
                .blur(44.dp)
                .background(RoseMist.copy(alpha = 0.16f), CircleShape)
        )

        // Main content — padded for header (top ~80dp) and bottom nav (~80dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 96.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Hero text
            Text(
                text = "Ready to\nRecord",
                style = MaterialTheme.typography.headlineLarge,
                color = Ink,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "One Tap to Mocap",
                style = MaterialTheme.typography.bodyLarge,
                color = Slate,
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Big SETUP & CALIBRATE button — disappears when calibrating
            AnimatedVisibility(visible = !isCalibrating) {
                val transition = rememberInfiniteTransition(label = "setupBtn")
                val pulse by transition.animateFloat(
                    initialValue = 0.92f, targetValue = 1.06f,
                    animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
                    label = "btnPulse",
                )

                Box(contentAlignment = Alignment.Center) {
                    // Pulsing glow ring behind button
                    Canvas(modifier = Modifier.size(320.dp)) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Mint.copy(alpha = 0.14f), Color.Transparent)
                            ),
                            radius = size.minDimension * 0.5f * pulse,
                        )
                        drawCircle(
                            color = MintBright.copy(alpha = 0.20f),
                            radius = size.minDimension * 0.45f * pulse,
                            style = Stroke(width = size.minDimension * 0.02f),
                        )
                    }
                    // Button
