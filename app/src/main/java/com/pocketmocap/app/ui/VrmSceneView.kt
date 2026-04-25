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
