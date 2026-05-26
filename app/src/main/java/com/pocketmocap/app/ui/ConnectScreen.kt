package com.pocketmocap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

            Spacer(modifier = Modifier.height(24.dp))
            PocapEyebrow("Step 1 - connect")
            PocapDecoratedHeadline(
                lines = listOf("Link this", "phone to"),
                decoratedLine = "Pocap PC.",
                decoratorColor = PocapCyan,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Scan the PC QR or type the IPv4 server URL. Session code comes next.",
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))
            PocapCard {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PocapEyebrow("PC server link")
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Pocap PC link or IPv4 URL", style = MaterialTheme.typography.bodySmall) },
                        placeholder = { Text("http://<PC IPv4>:8090", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
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
                    PocapButton(
                        label = "Scan QR",
                        onClick = onScanQr,
                        modifier = Modifier.fillMaxWidth(),
                        tone = PocapPaperLight,
                        contentColor = PocapInk,
                    )
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
                Spacer(modifier = Modifier.height(10.dp))
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PocapChip(label = connectionStatus(uiState), tone = connectionTone(uiState), dot = true)
                    Text(
                        text = "PC controls sessions and recording. This phone only streams camera landmarks and metrics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapInk2,
                        fontWeight = FontWeight.SemiBold,
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
    ConnectionState.CONNECTING -> "Linking to PC..."
    ConnectionState.CONNECTED -> "Linked to PC"
    ConnectionState.DISCONNECTED -> "Link to Pocap PC"
}

private fun connectionStatus(uiState: UiState): String = when (uiState.connectionState) {
    ConnectionState.CONNECTED -> "pc linked"
    ConnectionState.CONNECTING -> "linking"
    ConnectionState.DISCONNECTED -> "not linked"
}

private fun connectionTone(uiState: UiState): Color = when (uiState.connectionState) {
    ConnectionState.CONNECTED -> PocapCyan
    ConnectionState.CONNECTING -> PocapViolet
    ConnectionState.DISCONNECTED -> PocapPaperLight
}
