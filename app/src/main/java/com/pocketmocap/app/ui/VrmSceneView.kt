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
