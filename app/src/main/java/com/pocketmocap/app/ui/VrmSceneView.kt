package com.pocketmocap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import com.google.android.filament.gltfio.FilamentInstance
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import kotlin.math.abs
import kotlin.math.sqrt

private const val PFXM = "vis_char_060:mixamorig:"

private object Bone {
    val HIPS = "${PFXM}Hips"
    val SPINE = "${PFXM}Spine"
    val SPINE1 = "${PFXM}Spine1"
    val SPINE2 = "${PFXM}Spine2"
    val NECK = "${PFXM}Neck"
    val HEAD = "${PFXM}Head"
    val L_UP_LEG = "${PFXM}LeftUpLeg"
    val L_LEG = "${PFXM}LeftLeg"
    val L_FOOT = "${PFXM}LeftFoot"
    val R_UP_LEG = "${PFXM}RightUpLeg"
    val R_LEG = "${PFXM}RightLeg"
    val R_FOOT = "${PFXM}RightFoot"
    val L_ARM = "${PFXM}LeftArm"
    val L_FORE = "${PFXM}LeftForeArm"
    val L_HAND = "${PFXM}LeftHand"
    val R_ARM = "${PFXM}RightArm"
    val R_FORE = "${PFXM}RightForeArm"
    val R_HAND = "${PFXM}RightHand"
}

private val BIND_DIR = mapOf(
    Bone.SPINE to floatArrayOf(0.0001f, 0.9863f, 0.1647f),
    Bone.SPINE1 to floatArrayOf(0.0000f, 0.9863f, 0.1647f),
    Bone.SPINE2 to floatArrayOf(0.0000f, 0.9863f, 0.1647f),
    Bone.NECK to floatArrayOf(0.0000f, 0.9863f, 0.1647f),
    Bone.HEAD to floatArrayOf(0.0000f, 0.9997f, 0.0259f),
    Bone.L_UP_LEG to floatArrayOf(-0.8925f, -0.4507f, 0.0192f),
    Bone.L_LEG to floatArrayOf(-0.0196f, -0.9988f, -0.0448f),
    Bone.L_FOOT to floatArrayOf(0.0229f, -0.9930f, 0.1155f),
    Bone.R_UP_LEG to floatArrayOf(0.8925f, -0.4510f, 0.0095f),
    Bone.R_LEG to floatArrayOf(0.0196f, -0.9995f, -0.0249f),
    Bone.R_FOOT to floatArrayOf(-0.0229f, -0.9951f, 0.0958f),
    Bone.L_ARM to floatArrayOf(-0.9631f, -0.2691f, 0.0006f),
    Bone.L_FORE to floatArrayOf(-1.0000f, 0.0000f, 0.0000f),
    Bone.L_HAND to floatArrayOf(-1.0000f, 0.0000f, 0.0000f),
    Bone.R_ARM to floatArrayOf(0.9631f, -0.2692f, -0.0006f),
    Bone.R_FORE to floatArrayOf(1.0000f, 0.0000f, 0.0000f),
    Bone.R_HAND to floatArrayOf(1.0000f, 0.0000f, 0.0000f),
)

private const val MP_NOSE = 0
private const val MP_L_SHOULDER = 11
private const val MP_R_SHOULDER = 12
private const val MP_L_ELBOW = 13
private const val MP_R_ELBOW = 14
private const val MP_L_WRIST = 15
private const val MP_R_WRIST = 16
private const val MP_L_HIP = 23
private const val MP_R_HIP = 24
private const val MP_L_KNEE = 25
private const val MP_R_KNEE = 26
private const val MP_L_ANKLE = 27
private const val MP_R_ANKLE = 28
private const val MP_L_HEEL = 29
private const val MP_R_HEEL = 30
private const val MP_L_FOOT_INDEX = 31
private const val MP_R_FOOT_INDEX = 32

@Composable
internal fun VrmSceneView(
    poseX: FloatArray?,
    poseY: FloatArray?,
    poseZ: FloatArray?,
    groundY: Float,
    subjectHeightMeters: Float,
    worldTracking: WorldTrackingSnapshot?,
    screenX: FloatArray?,
    screenY: FloatArray?,
    visibility: FloatArray?,
    useLateralOffset: Boolean = true,
    preferMetricPose: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var overlayDistanceMeters by remember { mutableFloatStateOf(2.25f) }
    var overlayBodyHeightMeters by remember {
        mutableFloatStateOf(
            if (subjectHeightMeters.isFinite() && subjectHeightMeters in 1.15f..2.15f) {
                subjectHeightMeters
            } else {
                1.68f
            }
        )
    }
    var overlayCameraHeightMeters by remember { mutableFloatStateOf(1.35f) }
    var overlayLateralOffsetMeters by remember { mutableFloatStateOf(0f) }
    var pendingOverlayFrames by remember { mutableIntStateOf(0) }
    var hasOverlayEstimate by remember { mutableStateOf(false) }

    val roi = remember(screenX, screenY, visibility) {
        computePoseRoi(screenX, screenY, visibility)
    }
    val overlayEstimate = remember(roi, screenX, screenY, visibility, subjectHeightMeters, worldTracking) {
        deriveOverlayPoseEstimate(
            roi = roi,
            screenX = screenX,
            screenY = screenY,
            visibility = visibility,
            rawSubjectHeightMeters = subjectHeightMeters,
            worldTracking = worldTracking,
        )
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(y = overlayCameraHeightMeters, z = 1.15f)
    }

    var modelNode by remember { mutableStateOf<ModelNode?>(null) }
    LaunchedEffect(Unit) {
        val instance: FilamentInstance? = modelLoader.loadModelInstance("angry.vrm")
        if (instance != null) {
            modelNode = ModelNode(
                modelInstance = instance,
                autoAnimate = false,
            )
        }
    }

    LaunchedEffect(overlayEstimate, preferMetricPose, subjectHeightMeters) {
        val metricHeight = subjectHeightMeters.takeIf { it.isFinite() && it in 1.15f..2.25f }
        if (preferMetricPose && metricHeight != null) {
            val estimate = overlayEstimate
            val nextDistance = estimate?.distanceMeters
                ?: worldTracking?.subjectDistanceMeters?.takeIf { it.isFinite() && it in 0.35f..12f }
                ?: overlayDistanceMeters
            val nextCameraHeight = estimate?.cameraHeightMeters
                ?: worldTracking?.cameraHeightMeters?.takeIf { it.isFinite() && it in 0.45f..2.30f }
                ?: overlayCameraHeightMeters
            val nextLateral = if (useLateralOffset) {
                estimate?.lateralOffsetMeters
                    ?: worldTracking?.lateralOffsetMeters?.takeIf { it.isFinite() && abs(it) <= 2.5f }
                    ?: overlayLateralOffsetMeters
            } else {
                0f
            }
            if (!hasOverlayEstimate) {
                overlayDistanceMeters = nextDistance
                overlayBodyHeightMeters = metricHeight
                overlayCameraHeightMeters = nextCameraHeight
                overlayLateralOffsetMeters = nextLateral
                hasOverlayEstimate = true
                pendingOverlayFrames = 0
                return@LaunchedEffect
            }

            overlayDistanceMeters = overlayDistanceMeters * 0.82f + nextDistance * 0.18f
            overlayBodyHeightMeters = overlayBodyHeightMeters * 0.88f + metricHeight * 0.12f
            overlayCameraHeightMeters = overlayCameraHeightMeters * 0.84f + nextCameraHeight * 0.16f
            overlayLateralOffsetMeters =
                if (useLateralOffset) {
                    overlayLateralOffsetMeters * 0.82f + nextLateral * 0.18f
                } else {
                    0f
                }
            pendingOverlayFrames = 0
            return@LaunchedEffect
        }

        val estimate = overlayEstimate ?: return@LaunchedEffect
        if (!hasOverlayEstimate) {
            overlayDistanceMeters = estimate.distanceMeters
            overlayBodyHeightMeters = estimate.bodyHeightMeters
            overlayCameraHeightMeters = estimate.cameraHeightMeters
            overlayLateralOffsetMeters = if (useLateralOffset) estimate.lateralOffsetMeters else 0f
            hasOverlayEstimate = true
            pendingOverlayFrames = 0
            return@LaunchedEffect
        }

        val distanceDelta = abs(estimate.distanceMeters - overlayDistanceMeters)
        val heightDelta = abs(estimate.bodyHeightMeters - overlayBodyHeightMeters)
        val cameraHeightDelta = abs(estimate.cameraHeightMeters - overlayCameraHeightMeters)
        val lateralDelta = abs(estimate.lateralOffsetMeters - overlayLateralOffsetMeters)
        val meaningfulChange =
            distanceDelta >= 0.15f ||
                heightDelta >= 0.08f ||
                cameraHeightDelta >= 0.08f ||
                lateralDelta >= 0.08f

        pendingOverlayFrames = if (meaningfulChange) pendingOverlayFrames + 1 else 0
        if (pendingOverlayFrames < 3) {
            return@LaunchedEffect
        }

        overlayDistanceMeters = overlayDistanceMeters * 0.72f + estimate.distanceMeters * 0.28f
        overlayBodyHeightMeters = overlayBodyHeightMeters * 0.82f + estimate.bodyHeightMeters * 0.18f
        overlayCameraHeightMeters = overlayCameraHeightMeters * 0.72f + estimate.cameraHeightMeters * 0.28f
        overlayLateralOffsetMeters =
            if (useLateralOffset) {
                overlayLateralOffsetMeters * 0.68f + estimate.lateralOffsetMeters * 0.32f
            } else {
                0f
            }
        pendingOverlayFrames = 0
    }

    LaunchedEffect(overlayDistanceMeters, overlayBodyHeightMeters, overlayCameraHeightMeters) {
        if (useLateralOffset) {
            cameraNode.position = Position(
                x = 0f,
                y = overlayCameraHeightMeters,
                z = 1.15f,
            )
            cameraNode.lookAt(
                Position(
                    x = 0f,
                    y = (overlayBodyHeightMeters * 0.72f).coerceIn(0.95f, 1.45f),
                    z = -overlayDistanceMeters,
                )
            )
        } else {
            val framedY = (overlayBodyHeightMeters * 0.58f).coerceIn(0.92f, 1.32f)
