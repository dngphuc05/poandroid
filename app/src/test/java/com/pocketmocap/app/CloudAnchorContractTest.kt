package com.pocketmocap.app

import com.pocketmocap.app.calibration.CloudAnchorResult
import com.pocketmocap.app.network.normalizeLobbyPreset
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAnchorContractTest {
    private val appDir: File = locateAppDir()

    @Test
    fun cloudAnchorResultSerializesPoseForServerProtocol() {
        val result = CloudAnchorResult(
            state = "resolved",
            sharedAnchorId = "cloud-anchor-id",
            position = floatArrayOf(0.1f, 1.2f, -0.3f),
            rotation = floatArrayOf(0f, 0.5f, 0f, 0.866f),
            quality = 0.82f,
        )

        val json = result.poseJson()

        assertEquals("resolved", json.getString("state"))
        assertEquals("cloud-anchor-id", json.getString("shared_anchor_id"))
        assertEquals(3, json.getJSONArray("position").length())
        assertEquals(4, json.getJSONArray("rotation").length())
        assertTrue(json.getDouble("quality") > 0.8)
        assertTrue(result.isSuccess)
    }

    @Test
    fun androidUsesRealArcoreCloudAnchorApisWithoutPlaceholderIds() {
        val capture = appDir.resolve("src/main/java/com/pocketmocap/app/camera/ArCoreFrameCapture.kt").readText()
        val viewModel = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapViewModel.kt").readText()
        val client = appDir.resolve("src/main/java/com/pocketmocap/app/network/MocapServerClient.kt").readText()

        assertTrue(capture.contains("cloudAnchorMode = Config.CloudAnchorMode.ENABLED"))
        assertTrue(capture.contains("hostCloudAnchorAsync"))
        assertTrue(capture.contains("resolveCloudAnchorAsync"))
        assertTrue(capture.contains("estimateFeatureMapQualityForHosting"))
        assertTrue(viewModel.contains("fun bindCloudAnchorEngine"))
        assertTrue(viewModel.contains("state.joinedLobbyPreset != \"multi_live\""))
        assertTrue(viewModel.contains("serverClient.sendAnchorHosted"))
        assertTrue(viewModel.contains("serverClient.sendAnchorResolved"))
        assertTrue(client.contains("\"anchor_hosted\""))
        assertTrue(client.contains("\"anchor_resolved\""))
        assertTrue(client.contains("\"anchor_resolve_request\""))

        assertFalse(capture.contains("pending_cloud_anchor"))
        assertFalse(viewModel.contains("pending_cloud_anchor"))
        assertFalse(client.contains("pending_cloud_anchor"))
    }

    @Test
    fun lobbyPresetNormalizerAcceptsSingleCameraFromEveryServerShape() {
        assertEquals(
            "single_live",
            normalizeLobbyPreset(JSONObject("""{"preset":"single_live"}""")),
        )
        assertEquals(
            "single_live",
            normalizeLobbyPreset(JSONObject("""{"lobby":{"preset":"single_live"}}""")),
        )
        assertEquals(
            "single_live",
            normalizeLobbyPreset(JSONObject("""{"lobby":{"capture_preset":"single_live"}}""")),
        )
        assertEquals(
            "single_live",
            normalizeLobbyPreset(JSONObject("""{"lobby":{"max_devices":1}}""")),
        )
        assertEquals(
            "single_live",
            normalizeLobbyPreset(JSONObject("""{"max_devices":1}""")),
        )
        assertEquals(
            "multi_live",
            normalizeLobbyPreset(JSONObject("""{"preset":"unknown"}""")),
        )
    }

    @Test
    fun singleCameraSessionsAreProtectedFromCloudAnchorState() {
        val viewModel = appDir.resolve("src/main/java/com/pocketmocap/app/PocketMocapViewModel.kt").readText()

        assertTrue(viewModel.contains("joinedLobbyPreset = activePreset"))
        assertTrue(viewModel.contains("val activePreset = lobbyPreset.takeIf"))
        assertTrue(viewModel.contains("if (_uiState.value.joinedLobbyPreset != \"multi_live\")"))
        assertTrue(viewModel.contains("Ignoring Cloud Anchor resolve request for single-camera session"))
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
}
