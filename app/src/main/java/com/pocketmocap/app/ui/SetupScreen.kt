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
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .shadow(36.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MintDeep, Mint)))
                            .clickable {
                                if (uiState.vrmModels.isEmpty()) showVrmWarning = true
                                else onStartCalibration()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val label = if (uiState.calibrationStep == CalibrationStep.COMPLETE)
                            "RECALIBRATE" else "SETUP &\nCALIBRATE"
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Calibration status — when complete (idle after calibration)
            if (uiState.calibrationStep == CalibrationStep.COMPLETE && !isCalibrating) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Glass,
                    shadowElevation = 12.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("CALIBRATION STATUS", style = MaterialTheme.typography.labelMedium, color = Slate, modifier = Modifier.weight(1f))
                            Text("Complete", style = MaterialTheme.typography.labelMedium, color = MintDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        listOf("Intrinsic Calc", "Extrinsic Anchor", "Bootstrap").forEachIndexed { i, step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Mint.copy(alpha = 0.10f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Mint),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MintDeep)
                                }
                                Text(step, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
                                Icon(Icons.Rounded.Check, null, tint = MintDeep, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // Calibration panel slides up from bottom
        AnimatedVisibility(
            visible = isCalibrating,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)),
        ) {
            CalibrationPanel(
                step = uiState.calibrationStep,
                bootstrapProgress = uiState.bootstrapProgress,
            )
        }

        // Error toast
        uiState.errorMessage?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 108.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFF0F0),
                shadowElevation = 8.dp,
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

        // VRM warning dialog
        if (showVrmWarning) {
            AlertDialog(
                onDismissRequest = { showVrmWarning = false },
                title = { Text("No VRM Model") },
                text = {
                    Text("Add a VRM character model in the Library \u2192 Models tab before starting capture. The avatar overlay requires a VRM file.")
                },
                confirmButton = {
                    TextButton(onClick = { showVrmWarning = false; onStartCalibration() }) {
                        Text("Continue Anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showVrmWarning = false }) {
                        Text("Go to Library")
                    }
                },
            )
        }
    }
}

// ─── Calibration Panel (slides up from bottom) ──────────────────────────────

@Composable
private fun CalibrationPanel(
    step: CalibrationStep,
    bootstrapProgress: Float,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = Glass,
        shadowElevation = 24.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("CALIBRATING", style = MaterialTheme.typography.labelMedium, color = Slate)

            CalibrationStepRow(
                number = 1,
                label = "Intrinsic Calc",
                subtitle = "Reading camera focal length & pixel pitch",
                isDone = step.ordinal > CalibrationStep.INTRINSIC_CALC.ordinal,
                isActive = step == CalibrationStep.INTRINSIC_CALC,
            )

            CalibrationStepRow(
                number = 2,
                label = "Extrinsic Anchor",
                subtitle = "Setting phone as coordinate origin [0,0,0]",
                isDone = step.ordinal > CalibrationStep.EXTRINSIC_ANCHOR.ordinal,
                isActive = step == CalibrationStep.EXTRINSIC_ANCHOR,
            )

            CalibrationStepRow(
                number = 3,
                label = if (step == CalibrationStep.BOOTSTRAP) "Bootstrap (${(bootstrapProgress * 15).toInt()}/15)" else "Bootstrap",
                subtitle = "Stand still \u2013 warming up GRU & bone lengths",
                isDone = step == CalibrationStep.COMPLETE,
                isActive = step == CalibrationStep.BOOTSTRAP,
                progress = if (step == CalibrationStep.BOOTSTRAP) bootstrapProgress else null,
            )
        }
    }
}

@Composable
private fun CalibrationStepRow(
    number: Int,
    label: String,
    subtitle: String,
    isDone: Boolean,
    isActive: Boolean,
    progress: Float? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    isDone -> Mint.copy(alpha = 0.12f)
                    isActive -> Color.White
                    else -> Color(0xFFE5E9EB)
                }
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (isDone) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Mint),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, "Done", tint = MintDeep, modifier = Modifier.size(24.dp))
                }
            } else if (isActive) {
                SpinningArc()
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MintDeep)
            } else {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFD9DDE0)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = Slate)
                }
            }
        }

