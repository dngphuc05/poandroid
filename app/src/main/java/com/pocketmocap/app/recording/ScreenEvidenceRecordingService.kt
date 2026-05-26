package com.pocketmocap.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenEvidenceRecordingService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputTarget: RecordingOutputTarget? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> startRecording(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        if (mediaRecorder != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = projectionIntent(intent) ?: run {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: run {
            stopSelf()
            return
        }
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            },
            null,
        )

        val metrics = screenMetrics()
        val output = nextOutputTarget()
        val recorder = newMediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(metrics.widthPixels, metrics.heightPixels)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            output.applyTo(this)
            prepare()
        }

        val display = projection.createVirtualDisplay(
            "pocap-screen-evidence",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            null,
        )

        mediaProjection = projection
        mediaRecorder = recorder
        virtualDisplay = display
        outputTarget = output
        recorder.start()
    }

    private fun stopRecording() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { outputTarget?.finish(this) }
        outputTarget = null
        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Pocap screen evidence",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Pocap REC is active")
            .setContentText("Recording camera overlay and 2D landmarks to MP4.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun projectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun nextOutputTarget(): RecordingOutputTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "pocap_screen_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Pocap")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return RecordingOutputTarget.FilePath(nextOutputFile())
            val descriptor = contentResolver.openFileDescriptor(uri, "w")
                ?: return RecordingOutputTarget.FilePath(nextOutputFile())
            return RecordingOutputTarget.MediaStoreUri(uri, descriptor)
        }
        return RecordingOutputTarget.FilePath(nextOutputFile())
    }

    private fun nextOutputFile(): File {
        val root = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(filesDir, "movies")
        val dir = File(root, "pocap-screen-evidence").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "pocap_screen_$stamp.mp4")
    }

    private sealed class RecordingOutputTarget {
        abstract fun applyTo(recorder: MediaRecorder)
        abstract fun finish(context: Context)

        data class FilePath(private val file: File) : RecordingOutputTarget() {
            override fun applyTo(recorder: MediaRecorder) {
                recorder.setOutputFile(file.absolutePath)
            }

            override fun finish(context: Context) = Unit
        }

        data class MediaStoreUri(
            private val uri: android.net.Uri,
            private val descriptor: ParcelFileDescriptor,
        ) : RecordingOutputTarget() {
            override fun applyTo(recorder: MediaRecorder) {
                recorder.setOutputFile(descriptor.fileDescriptor)
            }

            override fun finish(context: Context) {
                runCatching { descriptor.close() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
            }
        }
    }

    companion object {
        private const val ACTION_START = "com.pocketmocap.app.recording.START_SCREEN_EVIDENCE"
        private const val ACTION_STOP = "com.pocketmocap.app.recording.STOP_SCREEN_EVIDENCE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "pocap_screen_evidence"
        private const val NOTIFICATION_ID = 260526

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenEvidenceRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenEvidenceRecordingService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
