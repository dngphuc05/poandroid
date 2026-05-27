package com.pocketmocap.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureNodeImplementationTest {
    private val appDir: File = locateAppDir()

    @Test
    fun mainSourceDoesNotShipWithDeveloperServerAddress() {
        val forbidden = listOf(
            "192.168.100.146",
            "10.0.0.42",
            "482931",
            "DEFAULT_SERVER_URL",
            "http://localhost",
            "http://127.0.0.1",
            "coming soon",
            "SkeletonOverlay",
            "pasted-",
        )

        val offenders = appDir.resolve("src/main")
            .walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "xml", "kts") }
            .flatMap { file ->
                val text = file.readText()
                forbidden
                    .filter { token -> token in text }
                    .map { token -> "${file.relativeTo(appDir)} contains $token" }
            }
            .toList()

        assertTrue("Forbidden hardcoded connection values: $offenders", offenders.isEmpty())
    }

    @Test
    fun serverFramePayloadKeepsMediaPipeAndMetricEvidenceKeys() {
        val source = appDir
            .resolve("src/main/java/com/pocketmocap/app/network/MocapServerClient.kt")
            .readText()

        val requiredNamedFrameKeys = listOf(
            "frame_index",
            "timestamp_us",
            "image_width",
            "image_height",
            "rotation_degrees",
            "landmarks",
            "world_tracking",
            "scene_metrics",
            "ml_image",
        )
        val requiredLandmarkKeys = listOf(
            "x",
            "y",
            "z",
            "x_metric",
            "y_metric",
            "z_metric",
            "visibility",
            "presence",
            "confidence",
        )
        val requiredCompactKeys = listOf(
            "fi",
            "ts",
            "iw",
            "ih",
            "rd",
            "lm",
            "wt",
            "sm",
            "mi",
        )
        val requiredMlCropKeys = listOf(
            "jpeg_base64",
            "source_width",
            "source_height",
            "crop_left",
            "crop_top",
            "crop_width",
            "crop_height",
            "crop_pad_ratio",
        )

        val missing = (
            requiredNamedFrameKeys +
                requiredLandmarkKeys +
                requiredCompactKeys +
                requiredMlCropKeys
            ).filterNot { source.containsJsonKey(it) }

        assertTrue("Missing frame payload keys: $missing", missing.isEmpty())
    }

    @Test
    fun phoneLinksToServerBeforeJoiningLobbyCode() {
        val app = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapApp.kt").readText()
        val vm = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapViewModel.kt").readText()
        val client = appDir.resolve("src/main/java/com/pocketmocap/app/network/MocapServerClient.kt").readText()
        val connect = appDir.resolve("src/main/java/com/pocketmocap/app/ui/ConnectScreen.kt").readText()
        val join = appDir.resolve("src/main/java/com/pocketmocap/app/ui/JoinSessionScreen.kt").readText()

        assertTrue(app.contains("uiState.connectionState != ConnectionState.CONNECTED -> ConnectScreen"))
        assertTrue(app.contains("uiState.lobbyJoinState != LobbyJoinState.JOINED -> JoinSessionScreen"))
        assertTrue(app.contains("onBackToLink = { viewModel.leaveSessionCodeEntry() }"))
        assertTrue(app.contains("onBackToJoin = { viewModel.leaveToJoinCode() }"))
        assertTrue(app.contains("else -> CaptureScreen"))
        assertTrue(app.contains("uiState.lobbyJoinState != LobbyJoinState.JOINED"))
        assertTrue(app.contains("viewModel.applyServerLink(raw)"))
        assertTrue(app.contains("viewModel.connect()"))
        assertTrue(vm.contains("enum class LobbyJoinState"))
        assertTrue(vm.contains("fun joinSession"))
        assertTrue(client.contains("\"session_join\""))
        assertTrue(client.contains("\"lobby_joined\""))
        assertTrue(connect.contains("Link this"))
        assertFalse(connect.contains("Join session"))
        assertFalse(connect.contains("Use Link"))
        assertTrue(connect.contains("Link to Pocap PC"))
        assertTrue(join.contains("6-digit code"))
        assertTrue(join.contains("Join PC session"))
        assertTrue(join.contains("LinkedServerCard"))
        assertTrue(join.contains("Delete digit"))
        assertTrue(join.contains("Clear code"))
        assertTrue(join.contains("KeyboardType.Number"))
    }


    @Test
    fun phoneUsesServerLobbyPresetForSingleAndMultiCameraFlow() {
        val client = appDir.resolve("src/main/java/com/pocketmocap/app/network/MocapServerClient.kt").readText()
        val capture = appDir.resolve("src/main/java/com/pocketmocap/app/ui/CaptureScreen.kt").readText()

        assertTrue(client.contains("fun normalizeLobbyPreset"))
        assertTrue(client.contains("fallback: String = \"single_live\""))
        assertTrue(client.contains("lobby?.optString(\"preset\""))
        assertTrue(client.contains("value == \"single_live\""))
        assertTrue(client.contains("value == \"multi_live\""))
        assertTrue(client.contains("listener.onLobbyJoined(code, name, preset)"))
        assertTrue(capture.contains("val sessionIsSingleCamera = uiState.joinedLobbyPreset == \"single_live\""))
        assertTrue(capture.contains("Set camera height, send calibration, then capture starts."))
        assertTrue(capture.contains("Sync phones, calibrate this camera, then capture starts."))
        assertTrue(capture.contains("Syncing multi-phone timing"))
        assertTrue(capture.contains("CalibrationStep.SYNC_WAIT"))
        assertTrue(capture.contains("CalibrationStep.FAILED"))
    }

    @Test
    fun phoneRuntimeDoesNotContainViewerOrAssetLibraryScreens() {
        val removedViewerFiles = listOf(
            "src/main/java/com/pocketmocap/app/ui/LibraryScreens.kt",
            "src/main/java/com/pocketmocap/app/ui/SetupScreen.kt",
            "src/main/java/com/pocketmocap/app/ui/SkeletonSurfaceView.kt",
            "src/main/java/com/pocketmocap/app/ui/VrmSceneView.kt",
            "src/main/java/com/pocketmocap/app/ui/components/BottomNav.kt",
            "src/main/java/com/pocketmocap/app/ui/components/ViewToggle.kt",
            "src/main/java/com/pocketmocap/app/capture/CaptureSessionRecorder.kt",
            "src/main/assets/angry.vrm",
            "src/main/assets/mocap_gru.onnx",
        )

        removedViewerFiles.forEach { relativePath ->
            assertFalse("$relativePath should not exist in the phone capture node", appDir.resolve(relativePath).exists())
        }
    }

    @Test
    fun launcherIconUsesPocapGlyphOnWhiteBackground() {
        val icon = appDir.resolve("src/main/res/mipmap-anydpi-v26/app_icon.xml").readText()
        val roundIcon = appDir.resolve("src/main/res/mipmap-anydpi-v26/app_icon_round.xml").readText()
        val logo = appDir.resolve("src/main/res/drawable/pocap_logo.xml").readText()
        val colors = appDir.resolve("src/main/res/values/colors.xml").readText()
        val uiKit = appDir.resolve("src/main/java/com/pocketmocap/app/ui/PocapPhoneUi.kt").readText()

        listOf(icon, roundIcon).forEach { xml ->
            assertTrue(xml.contains("@color/pocap_icon_background"))
            assertTrue(xml.contains("@drawable/pocap_logo"))
        }
        assertTrue(colors.contains("name=\"pocap_icon_background\""))
        assertTrue(colors.contains("#FFFFFF"))
        assertTrue(logo.contains("android:scaleX=\"0.86\""))
        assertTrue(logo.contains("android:scaleY=\"0.86\""))
        assertTrue(uiKit.contains("scale = 0.86f"))
    }

    @Test
    fun phoneScreensUseFrontendPrototypeLanguageWithoutMockCaptureData() {
        val uiKit = appDir.resolve("src/main/java/com/pocketmocap/app/ui/PocapPhoneUi.kt").readText()
        val connect = appDir.resolve("src/main/java/com/pocketmocap/app/ui/ConnectScreen.kt").readText()
        val join = appDir.resolve("src/main/java/com/pocketmocap/app/ui/JoinSessionScreen.kt").readText()
        val capture = appDir.resolve("src/main/java/com/pocketmocap/app/ui/CaptureScreen.kt").readText()
        val viewfinder = appDir.resolve("src/main/java/com/pocketmocap/app/ui/PocapViewfinder.kt").readText()
        val qr = appDir.resolve("src/main/java/com/pocketmocap/app/ui/QrLinkScannerScreen.kt").readText()

        val requiredUiTokens = listOf(
            "PocapPaper",
            "PocapPaperLight",
            "PocapInk",
            "PocapCyan",
            "PocapViolet",
            "PocapPink",
            "PocapCard",
            "PocapButton",
            "PocapLogoMark",
            "PocapDecoratedHeadline",
            "PocapIconButton",
            "PocapBigNum",
            "PocapProgressBar",
            "PocapFactBox",
            "PocapSignalBars",
            "PocapCornerBrackets",
            "PocapMockViewfinder",
            "PocapFloorMarkers",
            "PocapCameraGlyph",
        )
        val missingUiTokens = requiredUiTokens.filterNot { token -> token in uiKit || token in viewfinder }
        assertTrue("Missing native port tokens from frontend prototype: $missingUiTokens", missingUiTokens.isEmpty())

        val requiredScreenSignals = listOf(
            "PocapPaperScaffold" to connect,
            "PocapDecoratedHeadline" to connect,
            "PocapCard" to connect,
            "PocapButton" to connect,
            "PocapPaperScaffold" to join,
            "PocapDecoratedHeadline" to join,
            "SessionCodeInput" to join,
            "DigitBox" to join,
            "LinkedServerCard" to join,
            "ArCoreFrameCapture" to capture,
            "viewModel.onCameraFrame" to capture,
            "AndroidView" to capture,
            "RealLandmarkOverlay" to capture,
            "private enum class CaptureView" to capture,
            "CameraReadyChrome" to capture,
            "SessionErrorScreen" to capture,
            "PermissionScreen" to capture,
            "SessionPill" to capture,
            "WarningBanner" to capture,
            "MetricsRowCard" to capture,
            "PocapCameraScrim" to capture,
            "PocapCornerBrackets" to capture,
            "PocapBigNum" to capture,
            "PocapProgressBar" to capture,
            "CalibrationPayloadRow" to capture,
            "PocapPayloadRow" to capture,
            "QrCameraPreview" to qr,
        )
        val missingScreenSignals = requiredScreenSignals
            .filterNot { (token, source) -> token in source }
            .map { (token, _) -> token }
        assertTrue("Missing phone screen implementation signals: $missingScreenSignals", missingScreenSignals.isEmpty())
        assertTrue("Live capture must use real runtime counters", capture.contains("viewModel.framesSentToServer"))
        assertTrue("Sync screen must use runtime calibration state", capture.contains("uiState.calibrationStep"))
        assertTrue("Join headline must decorate the session word", join.contains("decoratedLine = \"session.\"") && join.contains("decoratorColor = PocapViolet"))
        assertTrue("Connect headline must decorate Pocap PC with cyan", connect.contains("decoratedLine = \"Pocap PC.\"") && connect.contains("decoratorColor = PocapCyan"))
        assertTrue("Phone capture screen must name PC-owned mocap recording", capture.contains("PC records mocap") && capture.contains("RecordStepScreen"))
        assertTrue("Phone capture screen must expose screen evidence REC", capture.contains("Text(\"REC\"") && capture.contains("SCREEN MP4"))
        assertTrue("Phone sync screen must expose metric camera height adjustment", capture.contains("CameraHeightControl") && capture.contains("metric camera height"))
        assertTrue("Camera height adjustment must use the metric pipeline setter", capture.contains("viewModel::setManualCameraHeightMeters"))
        assertTrue("Phone calibration must branch single-camera and multi-camera setup", capture.contains("SetupStepScreen") && capture.contains("single camera live") && capture.contains("multi camera live"))
        assertTrue("Phone calibration must send camera setup before live capture", capture.contains("Send camera calibration") && capture.contains("Send sync calibration") && capture.contains("Opening capture..."))
        assertTrue("Phone capture flow must expose step-based navigation", capture.contains("SessionStepScaffold") && capture.contains("Single-camera guided flow") && capture.contains("Multi-camera guided flow"))
        assertFalse("Connect intro copy must stay compact on phone viewports", connect.contains("Session joining happens on the next screen."))
        assertFalse("Connect screen must not expose a second typed-link action", connect.contains("Use Link"))
        assertFalse("Capture screen must not use unclear LOG button text", capture.contains("Text(\"LOG\""))
        assertFalse("Phone app must not expose the old local diagnostic recorder", capture.contains("DIAG"))
        assertFalse("Phone app must not include local capture CSV names", capture.contains("metrics.csv") || capture.contains("skeleton_2d_landmarks.csv") || capture.contains("technical_3d_landmarks.csv"))
        assertFalse("Phone sync screen must not claim direct sync ownership", capture.contains("label = \"Start sync\""))
        assertTrue("Phone app should expose explicit capture navigation controls", capture.contains("Text(\"REC\"") && capture.contains("Back to code") )
        assertFalse("Capture runtime must not draw the fake reference skeleton over the real camera", capture.contains("SkeletonOverlay("))
    }

    @Test
    fun cameraHeightAdjustmentFeedsArcoreMetricCapture() {
        val viewModel = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapViewModel.kt").readText()
        val captureScreen = appDir.resolve("src/main/java/com/pocketmocap/app/ui/CaptureScreen.kt").readText()
        val arcoreCapture = appDir.resolve("src/main/java/com/pocketmocap/app/camera/ArCoreFrameCapture.kt").readText()

        assertTrue(viewModel.contains("fun setManualCameraHeightMeters"))
        assertTrue(viewModel.contains("PREF_MANUAL_CAMERA_HEIGHT_M"))
        assertTrue(captureScreen.contains("CameraHeightControl"))
        assertTrue(captureScreen.contains("viewModel::setManualCameraHeightMeters"))
        assertTrue(captureScreen.contains("onCameraHeightChange(safeHeight - 0.01f)"))
        assertTrue(captureScreen.contains("onCameraHeightChange(safeHeight + 0.01f)"))
        assertTrue(arcoreCapture.contains("fun setManualCameraHeightMeters"))
        assertTrue(arcoreCapture.contains("cameraHeightMeters = cameraHeight"))
    }

    @Test
    fun screenEvidenceRecordingIsSeparateFromLandmarkStreamingPipeline() {
        val manifest = appDir.resolve("src/main/AndroidManifest.xml").readText()
        val mainActivity = appDir.resolve("src/main/java/com/pocketmocap/app/MainActivity.kt").readText()
        val app = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapApp.kt").readText()
        val viewModel = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapViewModel.kt").readText()
        val capture = appDir.resolve("src/main/java/com/pocketmocap/app/ui/CaptureScreen.kt").readText()
        val service = appDir.resolve("src/main/java/com/pocketmocap/app/recording/ScreenEvidenceRecordingService.kt").readText()
        val pipeline = appDir.resolve("src/main/java/com/pocketmocap/app/pipeline/HybridPosePipeline.kt").readText()

        assertTrue(manifest.contains("ScreenEvidenceRecordingService"))
        assertTrue(manifest.contains("android:process=\":screenrecorder\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaProjection\""))
        assertTrue(mainActivity.contains("MediaProjectionManager"))
        assertTrue(mainActivity.contains("onStartScreenEvidenceRecording"))
        assertTrue(app.contains("onStartScreenEvidenceRecording"))
        assertTrue(viewModel.contains("isScreenEvidenceRecording"))
        assertTrue(capture.contains("ScreenEvidenceRecordingChrome"))
        assertTrue(capture.contains("REC saves this phone screen, person, and 2D landmarks as MP4"))
        assertTrue(capture.contains("PocapChip(") && capture.contains("label = \"rec\""))
        assertTrue(service.contains("MediaRecorder.VideoEncoder.H264"))
        assertTrue(service.contains("ResultReceiver"))
        assertTrue(service.contains("RESULT_STARTED"))
        assertTrue(service.contains("notifyStarted(output.displayPath())"))
        assertTrue(service.contains("MediaStore.Video.Media.EXTERNAL_CONTENT_URI"))
        assertTrue(service.contains("Environment.DIRECTORY_MOVIES}/Pocap"))
        assertTrue(service.contains("Environment.DIRECTORY_MOVIES"))
        assertTrue(service.contains("pocap-screen-evidence"))
        assertTrue(mainActivity.contains("screenEvidenceReceiver"))
        assertTrue(viewModel.contains("serverClient.sendCaptureStart(phoneVideoPath)"))
        assertTrue(viewModel.contains("serverClient.sendCaptureStop()"))
        assertFalse("Realtime landmark processing must not own MP4 encoding", pipeline.contains("MediaRecorder"))
        assertFalse("Realtime landmark processing must not own screen projection", pipeline.contains("MediaProjection"))
    }

    @Test
    fun phoneUsesFullLandmarkerAndKeepsPredictedThirtyThreePointOverlayVisible() {
        val viewModel = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapViewModel.kt").readText()
        val pipeline = appDir.resolve("src/main/java/com/pocketmocap/app/pipeline/HybridPosePipeline.kt").readText()
        val kalman = appDir.resolve("src/main/java/com/pocketmocap/app/pipeline/LandmarkKalman2D.kt").readText()

        assertTrue(pipeline.contains("pose_landmarker_heavy.task"))
        assertTrue(viewModel.contains("LandmarkKalman2D(fps = 30f)"))
        assertFalse("Phone smoother must not use 60fps timing when runtime delivery is ~30fps", viewModel.contains("LandmarkKalman2D(fps = 60f)"))
        assertTrue(kalman.contains("class LandmarkKalman2D(fps: Float = 30f"))
        assertTrue(kalman.contains("velocity = (z - pPred) / dt"))
        assertFalse("Fast-motion snap must not zero measured velocity", kalman.contains("velocity = (z - position) / dt"))
        assertTrue(viewModel.contains("DISPLAY_PREDICTED_VIS"))
        assertTrue(viewModel.contains("_displayFullVis"))
        assertTrue(viewModel.contains("_completedX.copyOf()"))
        assertTrue(viewModel.contains("_completedY.copyOf()"))
        assertTrue(viewModel.contains("_displayFullVis.copyOf()"))
        assertTrue(viewModel.contains("directLandmarkCallback?.invoke("))
        assertFalse("Phone overlay must not drop back to observed-only hidden joints", viewModel.contains("val displayFrame = _observedDisplayFilter.update"))
    }

    private fun locateAppDir(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).canonicalFile
        var depth = 0
        while (depth < 6) {
            val candidate = if (current.name == "app") current else current.resolve("app")
            if (candidate.resolve("src/main/AndroidManifest.xml").isFile) {
                return candidate
            }
            current = current.parentFile ?: break
            depth += 1
        }
        error("Unable to locate Android app directory from $userDir")
    }

    private fun String.containsJsonKey(key: String): Boolean =
        contains("\"$key\"") || contains("\\\"$key\\\"")
}
