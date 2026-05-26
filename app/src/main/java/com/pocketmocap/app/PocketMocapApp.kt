package com.pocketmocap.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pocketmocap.app.PocketMocapViewModel.ConnectionState
import com.pocketmocap.app.PocketMocapViewModel.LobbyJoinState
import com.pocketmocap.app.ui.CaptureScreen
import com.pocketmocap.app.ui.ConnectScreen
import com.pocketmocap.app.ui.JoinSessionScreen
import com.pocketmocap.app.ui.QrLinkScannerScreen
import com.pocketmocap.app.ui.theme.PocketMocapTheme

@Composable
fun PocketMocapApp(
    viewModel: PocketMocapViewModel,
    onStartScreenEvidenceRecording: () -> Unit = {},
    onStopScreenEvidenceRecording: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var scanningServerLink by remember { mutableStateOf(false) }

    PocketMocapTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                scanningServerLink -> QrLinkScannerScreen(
                    onLinkScanned = { raw ->
                        if (viewModel.applyServerLink(raw)) {
                            viewModel.connect()
                        }
                        scanningServerLink = false
                    },
                    onCancel = { scanningServerLink = false },
                    onError = { message ->
                        viewModel.showError(message)
                        scanningServerLink = false
                    },
                )

                uiState.connectionState != ConnectionState.CONNECTED -> ConnectScreen(
                    uiState = uiState,
                    onConnect = { viewModel.connect(it) },
                    onScanQr = { scanningServerLink = true },
                    onClearError = { viewModel.clearError() },
                )

                uiState.lobbyJoinState != LobbyJoinState.JOINED -> JoinSessionScreen(
                    uiState = uiState,
                    onCodeChange = { viewModel.updateLobbyCode(it) },
                    onJoinSession = { viewModel.joinSession(it) },
                    onBackToLink = { viewModel.leaveSessionCodeEntry() },
                    onScanQr = { scanningServerLink = true },
                    onClearError = { viewModel.clearError() },
                )

                else -> CaptureScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    onStartCalibration = { viewModel.startCalibration() },
                    onLeaveSession = { viewModel.leaveJoinedSession() },
                    onBackToJoin = { viewModel.leaveToJoinCode() },
                    onBackToLink = { viewModel.disconnect() },
                    onClearError = { viewModel.clearError() },
                    onStartScreenEvidenceRecording = onStartScreenEvidenceRecording,
                    onStopScreenEvidenceRecording = onStopScreenEvidenceRecording,
                )
            }
        }
    }
}
