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
            cameraNode.position = Position(
                x = 0f,
                y = framedY,
                z = 2.05f,
            )
            cameraNode.lookAt(
                Position(
                    x = 0f,
                    y = framedY,
                    z = 0f,
                )
            )
        }
    }

    val boneCache = remember { mutableMapOf<String, Node?>() }
    LaunchedEffect(
        poseX,
        poseY,
        poseZ,
        groundY,
        subjectHeightMeters,
        overlayDistanceMeters,
        overlayBodyHeightMeters,
        overlayLateralOffsetMeters,
        useLateralOffset,
        preferMetricPose,
        modelNode,
    ) {
        val model = modelNode ?: return@LaunchedEffect
        val rawX = poseX ?: return@LaunchedEffect
        val rawY = poseY ?: return@LaunchedEffect
        val rawZ = poseZ ?: return@LaunchedEffect
        if (rawX.size < 33 || rawY.size < 33 || rawZ.size < 33) return@LaunchedEffect

        var floor = if (groundY.isFinite()) groundY else robustVrmFloorY(rawY, visibility)
        var maxY = robustVrmTopY(rawY, visibility)
        var rootX = 0f
        var rootZ = 0f
        var rootCount = 0
        if (!floor.isFinite()) {
            floor = 0f
        }
        if (!maxY.isFinite()) {
            var fallbackTop = Float.NEGATIVE_INFINITY
            for (value in rawY) {
                if (value.isFinite()) fallbackTop = maxOf(fallbackTop, value)
            }
            maxY = fallbackTop
        }
        val rootIndices = if (preferMetricPose) {
            intArrayOf(MP_L_HIP, MP_R_HIP)
        } else {
            intArrayOf(MP_L_HIP, MP_R_HIP, MP_L_SHOULDER, MP_R_SHOULDER)
        }
        for (idx in rootIndices) {
            val visible = visibility?.getOrNull(idx) ?: 1f
            if (visible <= 0.2f) continue
            rootX += rawX[idx]
            rootZ += rawZ[idx]
            rootCount += 1
        }
        if (rootCount > 0) {
            rootX /= rootCount
            rootZ /= rootCount
        }

        val rawHeight = when {
            maxY.isFinite() -> (maxY - floor).coerceAtLeast(0.60f)
            subjectHeightMeters.isFinite() && subjectHeightMeters in 0.75f..3.5f -> subjectHeightMeters
            else -> overlayBodyHeightMeters
        }
        val scale = if (preferMetricPose && overlayBodyHeightMeters.isFinite() && overlayBodyHeightMeters in 0.75f..3.5f) {
            (overlayBodyHeightMeters / rawHeight.coerceAtLeast(0.60f)).coerceIn(0.12f, 2.50f)
        } else {
            (overlayBodyHeightMeters / rawHeight.coerceAtLeast(0.60f)).coerceIn(0.35f, 2.50f)
        }

        val lateralOffset = if (useLateralOffset) overlayLateralOffsetMeters else 0f
        val depthAnchor = if (useLateralOffset) -overlayDistanceMeters else 0f
        val depthScale = if (useLateralOffset) 1f else 0.58f
        val tx = FloatArray(33)
        val ty = FloatArray(33)
        val tz = FloatArray(33)
        for (i in 0 until 33) {
            tx[i] = lateralOffset + (rawX[i] - rootX) * scale
            ty[i] = (rawY[i] - floor) * scale
            tz[i] = depthAnchor + (rawZ[i] - rootZ) * scale * depthScale
        }

        retargetVrmBones(model, boneCache, tx, ty, tz, visibility)
    }

    Box(modifier = modifier) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            isOpaque = false,
            childNodes = listOfNotNull(modelNode),
        )
    }
}

private fun robustVrmFloorY(rawY: FloatArray, visibility: FloatArray?): Float {
    fun minVisible(indices: IntArray, minVisibility: Float): Float {
        var value = Float.POSITIVE_INFINITY
        for (idx in indices) {
            val visible = visibility?.getOrNull(idx) ?: 1f
            val y = rawY.getOrNull(idx) ?: continue
            if (visible > minVisibility && y.isFinite()) value = minOf(value, y)
        }
        return value
    }
    val foot = minVisible(
        intArrayOf(MP_L_ANKLE, MP_R_ANKLE, MP_L_HEEL, MP_R_HEEL, MP_L_FOOT_INDEX, MP_R_FOOT_INDEX),
        0.18f,
    )
    if (foot.isFinite()) return foot
    val lower = minVisible(intArrayOf(MP_L_KNEE, MP_R_KNEE, MP_L_HIP, MP_R_HIP), 0.22f)
    return if (lower.isFinite()) lower else Float.POSITIVE_INFINITY
}

private fun robustVrmTopY(rawY: FloatArray, visibility: FloatArray?): Float {
    fun maxVisible(indices: IntArray, minVisibility: Float): Float {
        var value = Float.NEGATIVE_INFINITY
        for (idx in indices) {
            val visible = visibility?.getOrNull(idx) ?: 1f
            val y = rawY.getOrNull(idx) ?: continue
            if (visible > minVisibility && y.isFinite()) value = maxOf(value, y)
        }
        return value
    }
    val head = maxVisible(intArrayOf(MP_NOSE), 0.18f)
    if (head.isFinite()) return head
    val torso = maxVisible(intArrayOf(MP_L_SHOULDER, MP_R_SHOULDER, MP_L_HIP, MP_R_HIP), 0.22f)
    return if (torso.isFinite()) torso else Float.NEGATIVE_INFINITY
}

private fun retargetVrmBones(
    model: ModelNode,
    cache: MutableMap<String, Node?>,
    px: FloatArray,
    py: FloatArray,
    pz: FloatArray,
    vis: FloatArray?,
) {
    fun vrmP(i: Int) = Triple(px[i], py[i], pz[i])
    fun v(i: Int) = vis?.getOrNull(i) ?: 0.8f
    fun dirVec(from: Triple<Float, Float, Float>, to: Triple<Float, Float, Float>): FloatArray? {
        val dx = to.first - from.first
        val dy = to.second - from.second
        val dz = to.third - from.third
        return norm3(dx, dy, dz)
    }
    fun dirJoints(a: Int, b: Int): FloatArray? {
        if (v(a) < 0.3f || v(b) < 0.3f) return null
        return dirVec(vrmP(a), vrmP(b))
    }
    fun setBoneDirection(boneName: String, dir: FloatArray?) {
        if (dir == null) return
        val bind = BIND_DIR[boneName] ?: return
        val node = cache.getOrPut(boneName) { model.nodes.find { it.name == boneName } } ?: return
        val parentWorld = node.parent?.worldQuaternion?.let(::quatToArray) ?: floatArrayOf(0f, 0f, 0f, 1f)
        val localTarget = quatRotateVector(quatConjugate(parentWorld), dir) ?: return
        val q = quatShortestArc(bind, localTarget)
        node.quaternion = Quaternion(q[0], q[1], q[2], q[3])
    }

    val hipMid = Triple(
        (px[MP_L_HIP] + px[MP_R_HIP]) * 0.5f,
        (py[MP_L_HIP] + py[MP_R_HIP]) * 0.5f,
        (pz[MP_L_HIP] + pz[MP_R_HIP]) * 0.5f,
    )
    val shoulderMid = Triple(
        (px[MP_L_SHOULDER] + px[MP_R_SHOULDER]) * 0.5f,
        (py[MP_L_SHOULDER] + py[MP_R_SHOULDER]) * 0.5f,
        (pz[MP_L_SHOULDER] + pz[MP_R_SHOULDER]) * 0.5f,
    )

    val hipsNode = cache.getOrPut(Bone.HIPS) { model.nodes.find { it.name == Bone.HIPS } }
    val pelvisRight = dirJoints(MP_L_HIP, MP_R_HIP)
    val spineDir = dirVec(hipMid, shoulderMid)
    if (hipsNode != null) {
        hipsNode.position = Position(x = 0f, y = 0f, z = 0f)
        if (pelvisRight != null && spineDir != null) {
            val pelvisBack = cross3(pelvisRight, spineDir)?.let { norm3(it[0], it[1], it[2]) }
            if (pelvisBack != null) {
                val q = quatFromBasis(pelvisRight, spineDir, pelvisBack)
                hipsNode.quaternion = Quaternion(q[0], q[1], q[2], q[3])
            }
        }
    }

    setBoneDirection(Bone.SPINE, spineDir)
    setBoneDirection(Bone.SPINE1, spineDir)
    setBoneDirection(Bone.SPINE2, spineDir)

    val headDir = dirVec(shoulderMid, vrmP(MP_NOSE))
    setBoneDirection(Bone.NECK, headDir)
    setBoneDirection(Bone.HEAD, headDir)

    setBoneDirection(Bone.L_UP_LEG, dirJoints(MP_L_HIP, MP_L_KNEE))
    setBoneDirection(Bone.L_LEG, dirJoints(MP_L_KNEE, MP_L_ANKLE))
    setBoneDirection(Bone.L_FOOT, dirJoints(MP_L_ANKLE, MP_L_FOOT_INDEX) ?: dirJoints(MP_L_ANKLE, MP_L_HEEL))

    setBoneDirection(Bone.R_UP_LEG, dirJoints(MP_R_HIP, MP_R_KNEE))
    setBoneDirection(Bone.R_LEG, dirJoints(MP_R_KNEE, MP_R_ANKLE))
    setBoneDirection(Bone.R_FOOT, dirJoints(MP_R_ANKLE, MP_R_FOOT_INDEX) ?: dirJoints(MP_R_ANKLE, MP_R_HEEL))

    setBoneDirection(Bone.L_ARM, dirJoints(MP_L_SHOULDER, MP_L_ELBOW))
    setBoneDirection(Bone.L_FORE, dirJoints(MP_L_ELBOW, MP_L_WRIST))
    setBoneDirection(Bone.L_HAND, dirJoints(MP_L_ELBOW, MP_L_WRIST))

    setBoneDirection(Bone.R_ARM, dirJoints(MP_R_SHOULDER, MP_R_ELBOW))
    setBoneDirection(Bone.R_FORE, dirJoints(MP_R_ELBOW, MP_R_WRIST))
    setBoneDirection(Bone.R_HAND, dirJoints(MP_R_ELBOW, MP_R_WRIST))

    val leftFootDir = dirJoints(MP_L_ANKLE, MP_L_FOOT_INDEX) ?: dirJoints(MP_L_ANKLE, MP_L_HEEL)
    val rightFootDir = dirJoints(MP_R_ANKLE, MP_R_FOOT_INDEX) ?: dirJoints(MP_R_ANKLE, MP_R_HEEL)
    setBoneDirection(Bone.L_FOOT, leftFootDir)
    setBoneDirection(Bone.R_FOOT, rightFootDir)

    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (i in 0 until 33) {
        if (v(i) < 0.2f) continue
        minY = minOf(minY, py[i])
        maxY = maxOf(maxY, py[i])
    }
    val bodyHeight = if (minY.isFinite() && maxY.isFinite()) {
        (maxY - minY).coerceAtLeast(0.85f)
    } else {
        1.68f
    }
    val scaleFactor = (bodyHeight / 1.68f).coerceIn(0.78f, 1.12f)
    model.setScale(scaleFactor)
    model.position = Position(
        x = hipMid.first,
        y = 0f,
        z = hipMid.third,
    )
}

private fun norm3(x: Float, y: Float, z: Float): FloatArray? {
    val length = sqrt(x * x + y * y + z * z)
    if (length < 1e-6f) return null
    return floatArrayOf(x / length, y / length, z / length)
}

private fun cross3(a: FloatArray, b: FloatArray): FloatArray? {
