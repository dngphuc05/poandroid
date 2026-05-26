package com.pocketmocap.app.ui

import com.pocketmocap.app.ui.theme.PocapMono
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.PocketMocapViewModel.LobbyJoinState

@Composable
fun JoinSessionScreen(
    uiState: PocketMocapViewModel.UiState,
    onCodeChange: (String) -> Unit,
    onJoinSession: (String) -> Unit,
    onDisconnect: () -> Unit,
    onClearError: () -> Unit,
) {
    val code = uiState.lobbyCodeInput.filter(Char::isDigit).take(6)
    val ready = code.length == 6
    val joining = uiState.lobbyJoinState == LobbyJoinState.JOINING

    PocapPaperScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            JoinBrandRow()

            Spacer(modifier = Modifier.height(28.dp))
            PocapEyebrow("Step 2 - session")
            PocapDecoratedHeadline(
                lines = listOf("Join a", "capture"),
                decoratedLine = "session.",
                decoratorColor = PocapViolet,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Enter the 6-digit code shown on Pocap PC. This phone will join as one capture camera.",
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                modifier = Modifier.padding(top = 14.dp),
            )

            Spacer(modifier = Modifier.height(22.dp))
            SessionCodeInput(
                code = code,
                hasError = uiState.errorMessage != null,
                onCodeChange = onCodeChange,
            )

            uiState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(10.dp))
                PocapCard(color = Color(0xFFFFEFEF), shadow = false) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapDanger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClearError)
                            .padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            LinkedServerCard(serverUrl = uiState.serverUrl)

            Spacer(modifier = Modifier.weight(1f))
            PocapButton(
                label = if (joining) "Joining PC session..." else "Join PC session",
                onClick = { onJoinSession(code) },
                enabled = ready && !joining,
                modifier = Modifier.fillMaxWidth(),
                tone = if (ready) PocapCyan else PocapPaperDeep,
                contentColor = PocapInk,
            )
            Text(
                text = "After joining, camera capture opens. The PC remains in control.",
                style = MaterialTheme.typography.bodySmall,
                color = PocapInk3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Change PC link",
                style = MaterialTheme.typography.bodySmall,
                color = PocapInk2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDisconnect)
                    .padding(top = 10.dp, bottom = 2.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun JoinBrandRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PocapLogoMark()
        Text(
            text = "Pocap",
            style = MaterialTheme.typography.titleMedium,
            color = PocapInk,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))
        PocapChip(label = "linked", tone = PocapCyan, dot = true)
    }
}

@Composable
private fun SessionCodeInput(
    code: String,
    hasError: Boolean,
    onCodeChange: (String) -> Unit,
) {
    BasicTextField(
        value = code,
        onValueChange = { value -> onCodeChange(value.filter(Char::isDigit).take(6)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(6) { index ->
                    DigitBox(
                        digit = code.getOrNull(index)?.toString().orEmpty(),
                        active = index == code.length.coerceIn(0, 5),
                        hasError = hasError,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
private fun DigitBox(
    digit: String,
    active: Boolean,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    val filled = digit.isNotBlank()
    val shape = RoundedCornerShape(12.dp)
    val underline = when {
        hasError -> PocapDanger
        active -> PocapViolet
        filled -> PocapCyan
        else -> Color.Transparent
    }
    Box(modifier = modifier.height(64.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(start = 2.dp, top = 2.dp)
                .clip(shape)
                .background(PocapInk),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (filled) PocapPaperLight else PocapPaperDeep)
                .border(2.dp, PocapInk, shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (active || hasError || filled) 5.dp else 0.dp)
                    .background(underline),
            )
            Text(
                text = digit.ifBlank { if (active) "|" else "." },
                style = MaterialTheme.typography.headlineSmall,
                color = if (filled || active) PocapInk else PocapInk4,
                fontFamily = PocapMono,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LinkedServerCard(serverUrl: String) {
    PocapCard(color = PocapPaperLight, shadow = false) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PocapLogoMark(modifier = Modifier.size(24.dp), color = PocapCyan)
            Column(modifier = Modifier.weight(1f)) {
                PocapEyebrow("PC link ready")
                Text(
                    text = serverUrl.ifBlank { "waiting for PC link" },
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk,
                    fontFamily = PocapMono,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PocapChip(label = "linked", tone = PocapCyan, dot = true)
        }
    }
}
