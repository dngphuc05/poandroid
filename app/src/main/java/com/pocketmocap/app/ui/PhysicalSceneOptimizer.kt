package com.pocketmocap.app.ui

import kotlin.math.abs

internal data class PhysicalSceneOptimizerInput(
    val confidence: Float,
    val rawDistanceMeters: Float,
    val rawHeightMeters: Float,
    val rawCameraHeightMeters: Float,
    val rawHipDepthDistanceMeters: Float,
    val footPlaneDistanceMeters: Float,
    val roiDistanceMeters: Float,
    val topRayHeightMeters: Float,
    val pixelSpanHeightMeters: Float,
    val hipGeometryDistanceMeters: Float = Float.NaN,
    val hipGeometryHeightMeters: Float = Float.NaN,
    val torsoHeightMeters: Float = Float.NaN,
    val groundedFootDistanceMeters: Float = Float.NaN,
    val bodyScaleConfidence: Float = Float.NaN,
    val boneLengthSpreadMeters: Float = Float.NaN,
    val relativeScaleDistanceMeters: Float = Float.NaN,
    val previousDistanceMeters: Float,
    val previousHeightMeters: Float,
    val floorHeightBiasMeters: Float,
    val depthScale: Float,
    val depthOffsetMeters: Float,
    val heightEndpointBiasMeters: Float,
)

internal data class PhysicalSceneOptimizerResult(
    val distanceMeters: Float,
    val heightMeters: Float,
    val cameraHeightMeters: Float,
    val solverConfidence: Float,
    val solverResidualMeters: Float,
    val floorHeightBiasMeters: Float,
    val depthScale: Float,
    val depthOffsetMeters: Float,
    val heightEndpointBiasMeters: Float,
    val distanceCandidateSpreadMeters: Float,
    val heightCandidateSpreadMeters: Float,
    val distanceTrusted: Boolean,
    val heightTrusted: Boolean,
    val exportHeightConstraint: Boolean,
    val distanceConfidence: Float,
    val heightConfidence: Float,
    val floorConfidence: Float,
    val weightHip: Float,
    val weightHead: Float,
    val weightFoot: Float,
    val weightTorso: Float,
    val weightBone: Float,
    val weightDepth: Float,
    val weightRoi: Float,
    val weightTemporal: Float,
    val activeFactors: String,
)

internal object PhysicalSceneOptimizer {
    private const val MIN_BODY_HEIGHT_METERS = 1.15f
    private const val MAX_BODY_HEIGHT_METERS = 2.15f
    private const val MIN_HEIGHT_ENDPOINT_BIAS_METERS = -0.025f
    private const val MAX_HEIGHT_ENDPOINT_BIAS_METERS = 0.080f
    private const val MAX_HIP_RAW_TOP_STANDALONE_GAP_METERS = 0.100f
    private const val MAX_TOP_LOW_BIAS_MASK_GAP_METERS = 0.045f
    private const val MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS = 0.110f

    private const val FLAG_DISTANCE_TRUSTED = 1
    private const val FLAG_HEIGHT_TRUSTED = 2
    private const val FLAG_REJECTED_HIP = 4
    private const val FLAG_REJECTED_FOOT = 8
    private const val FACTOR_NATIVE_FALLBACK = 128
    private const val FACTOR_NATIVE_CERES = 256
    private const val FACTOR_TORSO = 512
    private const val FACTOR_GROUNDED_FOOT = 1024
    private const val FACTOR_BONE = 2048
    private const val FACTOR_RELATIVE_SCALE = 4096
    private const val FLAG_REJECTED_HIP_GEOMETRY = 16
    private const val PACKED_INPUT_VERSION = 3f

    private val nativeAvailable: Boolean = runCatching {
        System.loadLibrary("physical_scene_optimizer")
        true
    }.getOrDefault(false)

    fun optimize(input: PhysicalSceneOptimizerInput): PhysicalSceneOptimizerResult {
        if (nativeAvailable) {
            runCatching { decodeNative(nativeOptimize(encode(input)), input) }
                .getOrNull()
                ?.let { return it }
        }
        return fallbackOptimize(input)
    }

    private external fun nativeOptimize(values: FloatArray): FloatArray

    private fun encode(input: PhysicalSceneOptimizerInput): FloatArray = floatArrayOf(
        PACKED_INPUT_VERSION,
        input.confidence,
        input.rawDistanceMeters,
        input.rawHeightMeters,
        input.rawCameraHeightMeters,
        input.rawHipDepthDistanceMeters,
        input.footPlaneDistanceMeters,
        input.roiDistanceMeters,
        input.topRayHeightMeters,
        input.pixelSpanHeightMeters,
        input.hipGeometryDistanceMeters,
        input.hipGeometryHeightMeters,
        input.torsoHeightMeters,
        input.groundedFootDistanceMeters,
        input.bodyScaleConfidence,
        input.boneLengthSpreadMeters,
        input.relativeScaleDistanceMeters,
        input.previousDistanceMeters,
        input.previousHeightMeters,
        input.floorHeightBiasMeters,
        input.depthScale,
        input.depthOffsetMeters,
        input.heightEndpointBiasMeters,
    )

    private fun decodeNative(out: FloatArray, input: PhysicalSceneOptimizerInput): PhysicalSceneOptimizerResult {
        if (out.size < 14) return fallbackOptimize(input)
        val flags = out[11].toInt()
        val distanceConfidence = out.getOrNull(14) ?: ((if (flags and FLAG_DISTANCE_TRUSTED != 0) out[3] else out[3] * 0.55f).coerceIn(0f, 1f))
        val heightConfidence = out.getOrNull(15) ?: ((if (flags and FLAG_HEIGHT_TRUSTED != 0) out[3] else out[3] * 0.55f).coerceIn(0f, 1f))
        return PhysicalSceneOptimizerResult(
            distanceMeters = out[0],
            heightMeters = out[1],
            cameraHeightMeters = out[2],
            solverConfidence = out[3],
            solverResidualMeters = out[4],
            floorHeightBiasMeters = out[5],
            depthScale = out[6],
            depthOffsetMeters = out[7],
            heightEndpointBiasMeters = out[8],
            distanceCandidateSpreadMeters = out[9],
            heightCandidateSpreadMeters = out[10],
            distanceTrusted = flags and FLAG_DISTANCE_TRUSTED != 0,
            heightTrusted = flags and FLAG_HEIGHT_TRUSTED != 0,
            exportHeightConstraint = out[12] > 0.5f,
            distanceConfidence = distanceConfidence,
            heightConfidence = heightConfidence,
            floorConfidence = input.confidence,
            weightHip = out.getOrNull(17) ?: Float.NaN,
            weightHead = out.getOrNull(18) ?: Float.NaN,
            weightFoot = out.getOrNull(19) ?: Float.NaN,
            weightTorso = out.getOrNull(20) ?: Float.NaN,
            weightBone = out.getOrNull(21) ?: Float.NaN,
            weightDepth = out.getOrNull(22) ?: 0f,
            weightRoi = out.getOrNull(23) ?: Float.NaN,
            weightTemporal = out.getOrNull(24) ?: Float.NaN,
            activeFactors = factorString(flags, out[13].toInt()),
        )
    }

    private fun fallbackOptimize(input: PhysicalSceneOptimizerInput): PhysicalSceneOptimizerResult {
        val correctedHipDepth = input.rawHipDepthDistanceMeters
            .takeIf { it.isFinite() }
            ?.let { (it * input.depthScale + input.depthOffsetMeters).coerceIn(0.35f, 12.0f) }
        val footDistance = input.footPlaneDistanceMeters.takeIf { it.isFinite() }
        val groundedFootDistance = input.groundedFootDistanceMeters.takeIf { it.isFinite() }
        val hipGeometryDistance = input.hipGeometryDistanceMeters.takeIf { it.isFinite() }
        val hipGeometryHeight = validHeight(input.hipGeometryHeightMeters)
        val torsoHeight = validHeight(input.torsoHeightMeters)
        val roiDistance = input.roiDistanceMeters.takeIf { it.isFinite() }
        val relativeScaleDistance = input.relativeScaleDistanceMeters.takeIf { it.isFinite() }
        var flags = 0

        val footRoiStrictAgreement =
            footDistance != null &&
                roiDistance != null &&
                strictCandidateAgreement(footDistance, roiDistance, maxAbsDelta = 0.48f, maxRatio = 0.16f)
        val stableNonHipReference = stableNonHipDistanceReference(
            footDistance = footDistance,
            groundedFootDistance = groundedFootDistance,
            roiDistance = roiDistance,
            relativeScaleDistance = relativeScaleDistance,
            previousDistance = input.previousDistanceMeters.takeIf { it.isFinite() },
            footRoiStrictAgreement = footRoiStrictAgreement,
        )
        val hipReference = listOfNotNull(
            roiDistance,
            input.rawDistanceMeters.takeIf { it.isFinite() },
            relativeScaleDistance,
            input.previousDistanceMeters.takeIf { it.isFinite() },
            footDistance,
        ).minByOrNull { abs(it - (input.previousDistanceMeters.takeIf { previous -> previous.isFinite() } ?: it)) }
        val hipGeometryRejected =
            hipGeometryDistance != null &&
                (
                    (
                        stableNonHipReference != null &&
                            !strictCandidateAgreement(
                                hipGeometryDistance,
                                stableNonHipReference,
                                maxAbsDelta = 0.32f,
                                maxRatio = 0.16f,
                            )
                        ) ||
                        (
                            hipReference != null &&
                                hipGeometryDistance > hipReference &&
                                !candidateAgreement(hipGeometryDistance, hipReference) &&
                                (
                                    input.previousDistanceMeters.isFinite() ||
                                        roiDistance != null ||
                                        relativeScaleDistance != null ||
                                        footDistance != null
                                    )
                            )
                    )
        val usableHipGeometryDistance = hipGeometryDistance?.takeUnless { hipGeometryRejected }
        if (hipGeometryRejected) flags = flags or FLAG_REJECTED_HIP_GEOMETRY
        val rawDistanceRejected =
            hipGeometryRejected &&
                input.rawDistanceMeters.isFinite() &&
                stableNonHipReference != null &&
                !strictCandidateAgreement(
                    input.rawDistanceMeters,
                    stableNonHipReference,
                    maxAbsDelta = 0.36f,
                    maxRatio = 0.18f,
                )
        val usableRawDistance = input.rawDistanceMeters.takeIf { it.isFinite() && !rawDistanceRejected }

        val footReference = usableHipGeometryDistance ?: roiDistance ?: usableRawDistance
        val footRoiWild =
            footDistance != null &&
                footReference != null &&
                !(if (roiDistance != null) footRoiStrictAgreement else candidateAgreement(footDistance, footReference)) &&
                footDistance > footReference
        if (correctedHipDepth != null) flags = flags or FLAG_REJECTED_HIP

        val trustedFootDistance = footDistance?.takeIf { foot ->
            val agreesWithHipGeometry = usableHipGeometryDistance?.let { candidateAgreement(foot, it) } == true
            val agreesWithFused =
                roiDistance == null &&
                    usableRawDistance?.let { candidateAgreement(foot, it) } == true
            val agreesWithRoi = roiDistance != null && footRoiStrictAgreement
            val noReference = usableRawDistance == null && roiDistance == null && groundedFootDistance != null
            !footRoiWild && (agreesWithHipGeometry || agreesWithFused || agreesWithRoi || noReference)
        }
        if (footDistance != null && trustedFootDistance == null) flags = flags or FLAG_REJECTED_FOOT

        val footRoiDrift = if (footDistance != null && roiDistance != null) {
            abs(footDistance - roiDistance) / maxOf(footDistance, roiDistance, 1e-4f)
        } else {
            0f
        }
        val footHipStrictAgreement = trustedFootDistance != null &&
            usableHipGeometryDistance != null &&
            abs(trustedFootDistance - usableHipGeometryDistance) <= 0.30f &&
            abs(trustedFootDistance - usableHipGeometryDistance) / maxOf(trustedFootDistance, usableHipGeometryDistance, 1e-4f) <= 0.12f
        val footWeight = when {
            trustedFootDistance == null -> 0f
            groundedFootDistance != null && footHipStrictAgreement -> 0.22f
            groundedFootDistance != null && footRoiStrictAgreement -> 0.34f
            footRoiStrictAgreement -> 0.30f
            groundedFootDistance != null -> 0.10f
            usableHipGeometryDistance != null && !candidateAgreement(trustedFootDistance, usableHipGeometryDistance) -> 0.04f
            usableRawDistance != null && !candidateAgreement(trustedFootDistance, usableRawDistance) -> 0.05f
            roiDistance != null && footRoiDrift > 0.42f -> 0.06f
            roiDistance != null && footRoiDrift > 0.25f -> 0.10f
            roiDistance != null -> 0.12f
            else -> 0.16f
        }
        val hipGeometryWeight = when {
            usableHipGeometryDistance == null -> 0f
            input.confidence >= 0.70f -> 0.72f
            input.confidence >= 0.55f -> 0.58f
            else -> 0.38f
        }
        val roiWeight = when {
            roiDistance == null -> 0f
            footRoiStrictAgreement && usableHipGeometryDistance == null -> 0.24f
            footRoiStrictAgreement -> 0.16f
            usableHipGeometryDistance != null -> 0.020f
            usableRawDistance != null -> 0.030f
            trustedFootDistance == null && footDistance != null -> 0.10f
            trustedFootDistance != null -> 0.045f
            else -> 0.12f
        }
        val relativeWeight = when {
            relativeScaleDistance == null -> 0f
            footRoiStrictAgreement -> 0.035f
            usableHipGeometryDistance == null && trustedFootDistance == null -> 0.18f
            usableHipGeometryDistance == null -> 0.10f
            else -> 0.045f
        }
        val rawWeight = when {
            rawDistanceRejected -> 0f
            footRoiStrictAgreement && usableHipGeometryDistance == null -> 0.06f
            usableHipGeometryDistance != null -> 0.20f
            flags and FLAG_REJECTED_FOOT != 0 && roiDistance != null -> 0f
            flags and FLAG_REJECTED_FOOT != 0 -> 0.10f
            else -> 0.34f
        }

        val distanceFactors = buildList {
            usableHipGeometryDistance?.takeIf { hipGeometryWeight > 0f }?.let { add(FactorValue("hip_geometry", it, hipGeometryWeight)) }
            trustedFootDistance?.takeIf { footWeight > 0f }?.let { add(FactorValue("foot_plane", it, footWeight)) }
            usableRawDistance?.takeIf { rawWeight > 0f }?.let { add(FactorValue("fused_raw", it, rawWeight)) }
            roiDistance?.takeIf { roiWeight > 0f }?.let { add(FactorValue("roi", it, roiWeight)) }
            relativeScaleDistance?.takeIf { relativeWeight > 0f }?.let { add(FactorValue("relative_scale", it, relativeWeight)) }
        }
        val rawDistanceFactors = buildList {
            hipGeometryDistance?.let { add(FactorValue("raw_hip_geometry", it, 0.52f)) }
            footDistance?.let { add(FactorValue("raw_foot_plane", it, 0.16f)) }
            input.rawDistanceMeters.takeIf { it.isFinite() }?.let { add(FactorValue("raw_fused", it, 0.20f)) }
            roiDistance?.let { add(FactorValue("raw_roi", it, 0.04f)) }
            relativeScaleDistance?.let { add(FactorValue("raw_relative_scale", it, 0.10f)) }
        }
        val measuredDistance = robustWeightedAverage(distanceFactors)
            ?: input.rawDistanceMeters.takeIf { it.isFinite() }
            ?: input.previousDistanceMeters
        val distanceSpread = factorSpread(rawDistanceFactors)
        val stableFootRoiControl = footRoiStrictAgreement && usableHipGeometryDistance == null
        val distanceControlSpread = if (stableFootRoiControl) {
            factorSpread(distanceFactors)
        } else {
            distanceSpread
        }
        val distanceResidual = if (stableFootRoiControl) {
            weightedResidual(distanceFactors, measuredDistance)
        } else {
            weightedResidual(rawDistanceFactors, measuredDistance)
        }
        val distanceTrusted =
            distanceControlSpread <= 1.20f &&
                distanceResidual <= 0.75f
        if (distanceTrusted) flags = flags or FLAG_DISTANCE_TRUSTED

        val fallbackDistance = when {
            usableHipGeometryDistance != null &&
                usableHipGeometryDistance in 0.35f..12.0f &&
                (roiDistance == null || candidateAgreement(usableHipGeometryDistance, roiDistance)) -> usableHipGeometryDistance
            trustedFootDistance != null &&
                trustedFootDistance in 0.35f..12.0f -> trustedFootDistance
            roiDistance != null -> roiDistance
            relativeScaleDistance != null -> relativeScaleDistance
            usableRawDistance != null -> usableRawDistance
            else -> Float.NaN
        }
        val correctedDistance = stabilizeDistance(
            previous = input.previousDistanceMeters,
            measured = measuredDistance,
            fallback = fallbackDistance,
            trusted = distanceTrusted,
            spread = distanceControlSpread,
            confidence = input.confidence,
        )

        val previousHeight = validHeight(input.previousHeightMeters)
        val rawHeight = validHeight(input.rawHeightMeters)
        val rawTopHeight = validHeight(input.topRayHeightMeters)
        val rawPixelHeight = validHeight(input.pixelSpanHeightMeters)
        val usableHipGeometryHeight = hipGeometryHeight?.takeUnless {
            hipHeightConflictsWithRawTop(
                hipGeometryHeight = it,
                rawTopHeight = rawTopHeight,
                torsoHeight = torsoHeight,
                rawPixelHeight = rawPixelHeight,
            )
        }
        val heightAnchors = listOfNotNull(usableHipGeometryHeight, torsoHeight, previousHeight)
        val clampedEndpointBias = input.heightEndpointBiasMeters
            .coerceIn(MIN_HEIGHT_ENDPOINT_BIAS_METERS, MAX_HEIGHT_ENDPOINT_BIAS_METERS)
        val semanticEndpointBias = semanticEndpointBiasForFactors(
            positiveBias = clampedEndpointBias.coerceAtLeast(0f),
            rawTopHeight = rawTopHeight,
            rawPixelHeight = rawPixelHeight,
            hipGeometryHeight = usableHipGeometryHeight,
            torsoHeight = torsoHeight,
            previousHeight = previousHeight,
        )
        val topHeight = anchoredHeightFactor(
            value = input.topRayHeightMeters + semanticEndpointBias,
            anchors = heightAnchors,
            maxDelta = 0.18f,
        )
        val pixelHeight = anchoredHeightFactor(
            value = input.pixelSpanHeightMeters + semanticEndpointBias,
            anchors = heightAnchors,
            maxDelta = 0.22f,
        )
        val heightFactors = buildList {
            val hipDuplicatesTorso =
                usableHipGeometryHeight != null &&
                    torsoHeight != null &&
                    abs(usableHipGeometryHeight - torsoHeight) <= 0.015f
            usableHipGeometryHeight?.takeUnless { hipDuplicatesTorso }?.let { add(FactorValue("hip_geometry_height", it, 0.34f)) }
            topHeight?.let { add(FactorValue("top_ray", it, 0.32f)) }
            torsoHeight?.let { add(FactorValue("torso_height", it, 0.10f)) }
            rawHeight?.let { add(FactorValue("fused_raw_height", it, 0.08f)) }
            pixelHeight?.let { add(FactorValue("pixel_span", it, 0.035f)) }
        }
        val measuredHeight = robustWeightedAverage(heightFactors)
            ?: rawHeight
            ?: previousHeight
            ?: Float.NaN
        val heightSpread = factorSpread(heightFactors)
        val heightResidual = weightedResidual(heightFactors, measuredHeight)
        val bodyScaleOk = input.bodyScaleConfidence.takeIf { it.isFinite() }?.let { it >= 0.36f } ?: true
        val semanticHeightAgreement = hasSemanticHeightAgreement(
            hipGeometryHeight = usableHipGeometryHeight,
            torsoHeight = torsoHeight,
            topRayHeight = rawTopHeight ?: topHeight,
        )
        val heightTrusted =
            distanceTrusted &&
                bodyScaleOk &&
                semanticHeightAgreement &&
                heightSpread <= 0.22f &&
                heightResidual <= 0.45f
        if (heightTrusted) flags = flags or FLAG_HEIGHT_TRUSTED

        val correctedHeight = stabilizeHeight(input.previousHeightMeters, measuredHeight, heightTrusted, input.confidence)
        val residual = weightedResidual(rawDistanceFactors, correctedDistance) + weightedResidual(heightFactors, correctedHeight)
        val confidencePenalty = ((distanceControlSpread / 1.25f) + (heightSpread / 0.65f) + (residual / 0.55f))
            .coerceIn(0f, 1.2f)
        val explicitQualityPenalty = if (distanceTrusted && heightTrusted) 0f else 0.34f
        val solverConfidence = (input.confidence * (1f - confidencePenalty * 0.42f - explicitQualityPenalty))
            .coerceIn(0.08f, 0.96f)

        val depthOffset = input.depthOffsetMeters
        val heightBias = learnHeightEndpointBias(
            input = input,
            correctedHeight = correctedHeight,
            trusted = heightTrusted,
            rawTopHeight = rawTopHeight,
            rawPixelHeight = rawPixelHeight,
            hipGeometryHeight = usableHipGeometryHeight,
            torsoHeight = torsoHeight,
            previousHeight = previousHeight,
        )
        val floorBias = (input.floorHeightBiasMeters + (heightBias - clampedEndpointBias) * 0.20f)
            .coerceIn(-0.20f, 0.20f)

        return PhysicalSceneOptimizerResult(
            distanceMeters = correctedDistance,
            heightMeters = correctedHeight,
            cameraHeightMeters = (input.rawCameraHeightMeters + floorBias).takeIf { it.isFinite() } ?: input.rawCameraHeightMeters,
            solverConfidence = solverConfidence,
            solverResidualMeters = residual,
            floorHeightBiasMeters = floorBias,
            depthScale = input.depthScale.coerceIn(0.92f, 1.08f),
            depthOffsetMeters = depthOffset,
            heightEndpointBiasMeters = heightBias,
            distanceCandidateSpreadMeters = distanceSpread,
            heightCandidateSpreadMeters = heightSpread,
            distanceTrusted = distanceTrusted,
            heightTrusted = heightTrusted,
            exportHeightConstraint = heightTrusted || input.previousHeightMeters.isFinite(),
            distanceConfidence = (solverConfidence * if (distanceTrusted) 1f else 0.45f).coerceIn(0f, 1f),
            heightConfidence = (solverConfidence * if (heightTrusted) 1f else 0.45f).coerceIn(0f, 1f),
            floorConfidence = input.confidence,
            weightHip = if (usableHipGeometryHeight != null) hipGeometryWeight else 0f,
            weightHead = if (topHeight != null) 0.32f else 0f,
            weightFoot = footWeight,
            weightTorso = if (torsoHeight != null) 0.10f else 0f,
            weightBone = input.bodyScaleConfidence.takeIf { it.isFinite() } ?: 0f,
            weightDepth = 0f,
            weightRoi = roiWeight,
            weightTemporal = maxOf(
                if (input.previousHeightMeters.isFinite() || input.previousDistanceMeters.isFinite()) 1f else 0f,
                relativeWeight,
            ),
            activeFactors = (distanceFactors + heightFactors).map { it.name }
                .plus(if (flags and FLAG_REJECTED_HIP != 0) listOf("depth_debug_only") else emptyList())
                .plus(if (flags and FLAG_REJECTED_HIP_GEOMETRY != 0) listOf("rejected_hip_geometry") else emptyList())
                .plus(if (flags and FLAG_REJECTED_FOOT != 0) listOf("rejected_foot_plane") else emptyList())
                .plus(if (!distanceTrusted) listOf("untrusted_distance_spread") else emptyList())
                .plus(if (!heightTrusted) listOf("untrusted_height_spread") else emptyList())
                .plus(if (!semanticHeightAgreement) listOf("rejected_height_semantic_agreement") else emptyList())
                .plus(if (heightSpread > 0.22f) listOf("rejected_height_spread") else emptyList())
                .plus("kotlin_fallback_optimizer")
                .distinct()
                .joinToString("|"),
        )
    }

    private data class FactorValue(val name: String, val value: Float, val weight: Float)

    private fun validHeight(value: Float): Float? =
        value.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }

    private fun anchoredHeightFactor(value: Float, anchors: List<Float>, maxDelta: Float): Float? {
        val valid = validHeight(value) ?: return null
        if (anchors.isEmpty()) return valid
        return valid.takeIf { candidate -> anchors.any { abs(candidate - it) <= maxDelta } }
    }

    private fun hipHeightConflictsWithRawTop(
        hipGeometryHeight: Float,
        rawTopHeight: Float?,
        torsoHeight: Float?,
        rawPixelHeight: Float?,
    ): Boolean {
        val top = rawTopHeight ?: return false
        if (hipGeometryHeight <= top + MAX_HIP_RAW_TOP_STANDALONE_GAP_METERS) return false
        val independentSupport = listOfNotNull(torsoHeight, rawPixelHeight)
            .any { abs(it - hipGeometryHeight) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS }
        return !independentSupport
    }

    private fun semanticEndpointBiasForFactors(
        positiveBias: Float,
        rawTopHeight: Float?,
        rawPixelHeight: Float?,
        hipGeometryHeight: Float?,
        torsoHeight: Float?,
        previousHeight: Float?,
    ): Float {
        if (positiveBias <= 0f) return 0f
        val top = rawTopHeight ?: return 0f
        val biasedTop = top + positiveBias
        val independentSupport = listOfNotNull(torsoHeight, rawPixelHeight)
            .any { abs(it - biasedTop) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS }
        if (independentSupport) return positiveBias
        val maxAnchor = listOfNotNull(hipGeometryHeight, previousHeight).maxOrNull()
            ?: return positiveBias
        return if (top >= maxAnchor - MAX_TOP_LOW_BIAS_MASK_GAP_METERS) positiveBias else 0f
    }

    private fun stableNonHipDistanceReference(
        footDistance: Float?,
        groundedFootDistance: Float?,
        roiDistance: Float?,
        relativeScaleDistance: Float?,
        previousDistance: Float?,
        footRoiStrictAgreement: Boolean,
    ): Float? {
        val footRoiReference = if (
            footDistance != null &&
            roiDistance != null &&
            footRoiStrictAgreement
        ) {
            weightedPair(footDistance, 0.58f, roiDistance, 0.42f)
        } else {
            null
        }
        val relativeTemporalReference = if (
            relativeScaleDistance != null &&
            previousDistance != null &&
            strictCandidateAgreement(relativeScaleDistance, previousDistance, maxAbsDelta = 0.36f, maxRatio = 0.16f)
        ) {
            weightedPair(relativeScaleDistance, 0.72f, previousDistance, 0.28f)
        } else {
            null
        }
        if (footRoiReference != null && relativeTemporalReference != null) {
            return if (strictCandidateAgreement(footRoiReference, relativeTemporalReference, maxAbsDelta = 0.45f, maxRatio = 0.18f)) {
                weightedPair(relativeTemporalReference, 0.58f, footRoiReference, 0.42f)
            } else {
                footRoiReference
            }
        }
        relativeTemporalReference?.let { return it }
        footRoiReference?.let { return it }

        if (
            relativeScaleDistance != null &&
            footDistance != null &&
            strictCandidateAgreement(relativeScaleDistance, footDistance, maxAbsDelta = 0.42f, maxRatio = 0.18f)
        ) {
            return weightedPair(relativeScaleDistance, 0.62f, footDistance, 0.38f)
        }
        if (
            relativeScaleDistance != null &&
            roiDistance != null &&
            strictCandidateAgreement(relativeScaleDistance, roiDistance, maxAbsDelta = 0.42f, maxRatio = 0.18f)
        ) {
            return weightedPair(relativeScaleDistance, 0.62f, roiDistance, 0.38f)
        }
        if (
            previousDistance != null &&
            footDistance != null &&
            strictCandidateAgreement(previousDistance, footDistance, maxAbsDelta = 0.55f, maxRatio = 0.22f)
        ) {
            return weightedPair(previousDistance, 0.54f, footDistance, 0.46f)
        }
        if (
            previousDistance != null &&
            roiDistance != null &&
            strictCandidateAgreement(previousDistance, roiDistance, maxAbsDelta = 0.55f, maxRatio = 0.22f)
        ) {
            return weightedPair(previousDistance, 0.54f, roiDistance, 0.46f)
        }
        if (
            previousDistance != null &&
            groundedFootDistance != null &&
            strictCandidateAgreement(previousDistance, groundedFootDistance, maxAbsDelta = 0.55f, maxRatio = 0.22f)
        ) {
            return weightedPair(previousDistance, 0.54f, groundedFootDistance, 0.46f)
        }
        return null
    }

    private fun weightedPair(a: Float, aWeight: Float, b: Float, bWeight: Float): Float =
        (a * aWeight + b * bWeight) / (aWeight + bWeight)

    private fun hasSemanticHeightAgreement(
        hipGeometryHeight: Float?,
        torsoHeight: Float?,
        topRayHeight: Float?,
    ): Boolean {
        // Hip-geometry height already passes a 2-ray LSQ + anthropometric-ratio
        // check inside estimateBodyFromTopAndHipRays; treat it as sufficient on
        // its own when it lands in the valid range, is INDEPENDENT of torso
        // (not the same torso-ratio seed), and torso does not strongly
        // contradict it. The independence guard prevents a single value from
        // being counted as agreement with itself.
        val hipValid = hipGeometryHeight?.takeIf {
            it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS
        }
        val torsoFinite = torsoHeight?.takeIf { it.isFinite() }
        val hipIndependent = hipValid != null && (torsoFinite == null || abs(hipValid - torsoFinite) > 0.015f)
        val hipDoesNotConflict = hipValid != null && (torsoFinite == null || abs(hipValid - torsoFinite) <= 0.22f)
        val topFinite = topRayHeight?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val hipDoesNotMaskLowTop = hipValid != null &&
            (topFinite == null || hipValid <= topFinite + MAX_HIP_RAW_TOP_STANDALONE_GAP_METERS)
        if (hipIndependent && hipDoesNotConflict && hipDoesNotMaskLowTop) return true

        val values = independentSemanticHeightValues(hipGeometryHeight, torsoHeight, topRayHeight)
        if (values.size < 2) return false
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                if (abs(values[i] - values[j]) <= 0.08f) return true
            }
        }
        return values.size >= 3 && values.maxOrNull()!! - values.minOrNull()!! <= 0.14f
    }

    private fun independentSemanticHeightValues(
        hipGeometryHeight: Float?,
        torsoHeight: Float?,
        topRayHeight: Float?,
    ): List<Float> {
        val torso = torsoHeight?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val hip = hipGeometryHeight?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val top = topRayHeight?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        return buildList {
            torso?.let { add(it) }
            if (hip != null && (torso == null || abs(hip - torso) > 0.015f)) {
                add(hip)
            }
            top?.let { add(it) }
        }
    }

    private fun stabilizeDistance(
        previous: Float,
        measured: Float,
        fallback: Float?,
        trusted: Boolean,
        spread: Float,
        confidence: Float,
    ): Float {
        val validMeasured = measured.takeIf { it.isFinite() && it in 0.35f..12.0f } ?: return previous
        if (!previous.isFinite()) return fallback?.takeIf { !trusted && it.isFinite() } ?: validMeasured
        if (!trusted) {
            val support = fallback?.takeIf { it.isFinite() && it in 0.70f..12.0f } ?: return previous
            val rawDelta = support - previous
            val maxStep = if (abs(rawDelta) > 0.75f) 0.55f else 0.24f
            val alpha = if (abs(rawDelta) > 0.75f) 0.34f else 0.18f
            val delta = rawDelta.coerceIn(-maxStep, maxStep)
            return (previous + delta * alpha).coerceIn(0.35f, 12.0f)
        }
        val alpha = when {
            spread <= 0.45f && confidence >= 0.62f -> 0.24f
            spread <= 0.85f -> 0.14f
            else -> 0.055f
        }
        val maxStep = when {
            spread <= 0.45f -> 0.22f
            spread <= 0.85f -> 0.14f
            else -> 0.075f
        }
        val delta = (validMeasured - previous).coerceIn(-maxStep, maxStep)
        return (previous + delta * alpha).coerceIn(0.35f, 12.0f)
    }

    private fun stabilizeHeight(previous: Float, measured: Float, trusted: Boolean, confidence: Float): Float {
        val validMeasured = measured.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return previous.takeIf { it.isFinite() } ?: Float.NaN
        if (!previous.isFinite()) return validMeasured
        if (!trusted) return previous
        val delta = validMeasured - previous
        val alpha = when {
            confidence < 0.55f -> 0.015f
            abs(delta) <= 0.025f -> 0.10f
            abs(delta) <= 0.075f -> 0.045f
            abs(delta) <= 0.16f -> 0.018f
            else -> 0.004f
        }
        return (previous + delta * alpha).coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
    }

    private fun learnDepthOffset(input: PhysicalSceneOptimizerInput, correctedHipDepth: Float?): Float {
        val foot = input.footPlaneDistanceMeters.takeIf { it.isFinite() } ?: return input.depthOffsetMeters
        val corrected = correctedHipDepth ?: return input.depthOffsetMeters
        if (abs(corrected - foot) > 0.45f || input.confidence < 0.58f) return input.depthOffsetMeters
        val residual = (foot - corrected).coerceIn(-0.08f, 0.08f)
        return (input.depthOffsetMeters + residual * 0.030f).coerceIn(-0.30f, 0.30f)
    }

    private fun learnHeightEndpointBias(
        input: PhysicalSceneOptimizerInput,
        correctedHeight: Float,
        trusted: Boolean,
        rawTopHeight: Float?,
        rawPixelHeight: Float?,
        hipGeometryHeight: Float?,
        torsoHeight: Float?,
        previousHeight: Float?,
    ): Float {
        val currentBias = input.heightEndpointBiasMeters
