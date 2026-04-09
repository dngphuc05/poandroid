package com.pocketmocap.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import kotlin.math.min
import kotlin.math.sqrt
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketmocap.app.capture.CaptureSessionRecorder
import com.pocketmocap.app.network.LandmarkData
import com.pocketmocap.app.network.MocapServerClient
import com.pocketmocap.app.pipeline.BoneConstraintEngine
import com.pocketmocap.app.pipeline.HybridPosePipeline
