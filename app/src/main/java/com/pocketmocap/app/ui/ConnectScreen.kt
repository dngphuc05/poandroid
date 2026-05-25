package com.pocketmocap.app.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketmocap.app.PocketMocapViewModel.ConnectionState
import com.pocketmocap.app.PocketMocapViewModel.UiState

@Composable
fun ConnectScreen(
    uiState: UiState,
    onConnect: (String) -> Unit,
    onApplyLink: (String) -> Unit,
    onScanQr: () -> Unit,
    onClearError: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(uiState.serverUrl) }

    LaunchedEffect(uiState.serverUrl) {
        serverUrl = uiState.serverUrl
    }

    PocapPaperScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            BrandRow()

            Spacer(modifier = Modifier.height(28.dp))
            PocapEyebrow("Step 1 - connect")
            Text(
                text = "Link this\nphone to\nPocap PC.",
                style = MaterialTheme.typography.headlineLarge,
                color = PocapInk,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Scan the PC link or type the most likely IPv4 server address from ipconfig. Session joining happens on the next screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                modifier = Modifier.padding(top = 14.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
            PocapCard {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PocapEyebrow("PC server link")
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Pocap PC link or IPv4 URL") },
                        placeholder = { Text("http://<PC IPv4>:8090") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PocapInk,
                            unfocusedBorderColor = PocapInk4,
                            focusedContainerColor = PocapPaperLight,
                            unfocusedContainerColor = PocapPaperLight,
                            cursorColor = PocapInk,
                            focusedLabelColor = PocapInk,
                            unfocusedLabelColor = PocapInk3,
                        ),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PocapButton(
                            label = "Scan QR",
                            onClick = onScanQr,
                            modifier = Modifier.weight(1f),
                            tone = PocapPaperLight,
                            contentColor = PocapInk,
                        )
                        PocapButton(
                            label = "Use Link",
                            onClick = { onApplyLink(serverUrl) },
                            modifier = Modifier.weight(1f),
                            tone = PocapViolet,
                            contentColor = PocapInk,
                            enabled = serverUrl.isNotBlank(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            PocapButton(
                label = connectLabel(uiState.connectionState),
                onClick = { onConnect(serverUrl) },
                enabled = uiState.connectionState != ConnectionState.CONNECTING && serverUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                tone = if (serverUrl.isNotBlank()) PocapCyan else PocapPaperDeep,
                contentColor = PocapInk,
            )

            uiState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(14.dp))
                PocapCard(color = Color(0xFFFFEFEF), shadow = false) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapDanger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClearError)
                            .padding(14.dp)
                            .background(Color.Transparent),
                    )
                }
                Text(
                    text = "Tap the message area to clear after correcting the link.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                        .background(Color.Transparent),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            PocapCard(color = PocapPaperLight, shadow = false) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PocapChip(label = connectionStatus(uiState), tone = connectionTone(uiState), dot = true)
                    Text(
                        text = "Most controls stay on the PC. This device only captures camera, MediaPipe landmarks, world tracking, and scene metrics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapInk2,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandRow() {
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
        PocapChip(label = "phone", tone = PocapPaperLight)
    }
}

private fun connectLabel(connectionState: ConnectionState): String = when (connectionState) {
    ConnectionState.CONNECTING -> "Linking..."
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.DISCONNECTED -> "Link phone"
}

private fun connectionStatus(uiState: UiState): String = when (uiState.connectionState) {
    ConnectionState.CONNECTED -> "connected"
    ConnectionState.CONNECTING -> "linking"
    ConnectionState.DISCONNECTED -> "waiting"
}

private fun connectionTone(uiState: UiState): Color = when (uiState.connectionState) {
    ConnectionState.CONNECTED -> PocapCyan
    ConnectionState.CONNECTING -> PocapViolet
    ConnectionState.DISCONNECTED -> PocapPaperLight
}
