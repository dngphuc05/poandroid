package com.pocketmocap.app

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.pocketmocap.app.recording.ScreenEvidenceRecordingService

/**
 * Main entry point for the Pocap phone capture app.
 *
 * Hosts the Compose capture UI. Avatar/stage rendering belongs on the PC app.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: PocketMocapViewModel by viewModels()
    private val screenEvidenceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = ScreenEvidenceRecordingService.startIntent(this, result.resultCode, data)
            ContextCompat.startForegroundService(this, intent)
            viewModel.markScreenEvidenceRecordingStarted()
        } else {
            viewModel.markScreenEvidenceRecordingStopped("REC permission cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            PocketMocapApp(
                viewModel = viewModel,
                onStartScreenEvidenceRecording = ::requestScreenEvidenceRecording,
                onStopScreenEvidenceRecording = ::stopScreenEvidenceRecording,
            )
        }
    }

    override fun onDestroy() {
        stopScreenEvidenceRecording()
        viewModel.disconnect()
        super.onDestroy()
    }

    private fun requestScreenEvidenceRecording() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenEvidenceLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopScreenEvidenceRecording() {
        startService(ScreenEvidenceRecordingService.stopIntent(this))
        viewModel.markScreenEvidenceRecordingStopped()
    }
}
