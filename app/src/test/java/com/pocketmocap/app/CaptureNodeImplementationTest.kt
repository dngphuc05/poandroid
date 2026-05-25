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
            "FloorMarkers",
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
        assertTrue(join.contains("6-digit code"))
        assertTrue(join.contains("LinkedServerCard"))
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
        val colors = appDir.resolve("src/main/res/values/colors.xml").readText()

        listOf(icon, roundIcon).forEach { xml ->
            assertTrue(xml.contains("@color/pocap_icon_background"))
            assertTrue(xml.contains("@drawable/pocap_logo"))
        }
        assertTrue(colors.contains("name=\"pocap_icon_background\""))
        assertTrue(colors.contains("#FFFFFF"))
    }

    @Test
    fun phoneScreensUseFrontendPrototypeLanguageWithoutMockCaptureData() {
        val uiKit = appDir.resolve("src/main/java/com/pocketmocap/app/ui/PocapPhoneUi.kt").readText()
        val connect = appDir.resolve("src/main/java/com/pocketmocap/app/ui/ConnectScreen.kt").readText()
        val join = appDir.resolve("src/main/java/com/pocketmocap/app/ui/JoinSessionScreen.kt").readText()
        val capture = appDir.resolve("src/main/java/com/pocketmocap/app/ui/CaptureScreen.kt").readText()
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
            "PocapIconButton",
            "PocapBigNum",
            "PocapProgressBar",
            "PocapFactBox",
            "PocapSignalBars",
            "PocapCornerBrackets",
        )
        val missingUiTokens = requiredUiTokens.filterNot { token -> token in uiKit }
        assertTrue("Missing native port tokens from frontend prototype: $missingUiTokens", missingUiTokens.isEmpty())

        val requiredScreenSignals = listOf(
            "PocapPaperScaffold" to connect,
            "PocapCard" to connect,
            "PocapButton" to connect,
            "PocapPaperScaffold" to join,
            "SessionCodeInput" to join,
            "DigitBox" to join,
            "LinkedServerCard" to join,
            "ArCoreFrameCapture" to capture,
            "viewModel.onCameraFrame" to capture,
            "AndroidView" to capture,
            "RealLandmarkOverlay" to capture,
            "SessionPill" to capture,
            "WarningBanner" to capture,
            "MetricsRowCard" to capture,
            "DeviceActionCard" to capture,
            "LiveRecordingStrip" to capture,
            "PocapCameraScrim" to capture,
            "PocapCornerBrackets" to capture,
            "PocapBigNum" to capture,
            "PocapProgressBar" to capture,
            "PocapFactBox" to capture,
            "PocapPayloadRow" to capture,
            "QrCameraPreview" to qr,
        )
        val missingScreenSignals = requiredScreenSignals
            .filterNot { (token, source) -> token in source }
            .map { (token, _) -> token }
        assertTrue("Missing phone screen implementation signals: $missingScreenSignals", missingScreenSignals.isEmpty())
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
