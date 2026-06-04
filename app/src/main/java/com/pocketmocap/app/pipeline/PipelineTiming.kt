package com.pocketmocap.app.pipeline

object PipelineTiming {
    // The camera/MediaPipe path is effectively 30fps on the target Pixel path.
    // Sending faster than that creates uneven server state updates under load.
    const val SERVER_SEND_INTERVAL_MS = 33L
}
