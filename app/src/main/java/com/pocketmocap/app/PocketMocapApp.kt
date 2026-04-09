package com.pocketmocap.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pocketmocap.app.PocketMocapViewModel.ConnectionState
import com.pocketmocap.app.ui.CaptureScreen
import com.pocketmocap.app.ui.ConnectScreen
import com.pocketmocap.app.ui.LibraryCapturesScreen
import com.pocketmocap.app.ui.LibraryModelsScreen
import com.pocketmocap.app.ui.LibraryTab
import com.pocketmocap.app.ui.SetupScreen
import com.pocketmocap.app.ui.components.BottomNav
import com.pocketmocap.app.ui.components.CaptureView
import com.pocketmocap.app.ui.components.Header
import com.pocketmocap.app.ui.components.NavTab
import com.pocketmocap.app.ui.theme.PocketMocapTheme

@Composable
fun PocketMocapApp(viewModel: PocketMocapViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var activeTab by remember { mutableStateOf(NavTab.SETUP) }
    var captureView by remember { mutableStateOf(CaptureView.SKELETON) }
    var hasConnected by remember { mutableStateOf(false) }
    var libraryTab by remember { mutableStateOf(LibraryTab.CAPTURES) }
