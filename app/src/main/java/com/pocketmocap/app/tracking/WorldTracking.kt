package com.pocketmocap.app.tracking

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private const val MIN_AUTHORITATIVE_HEIGHT_METERS = 1.05f
private const val MAX_AUTHORITATIVE_HEIGHT_METERS = 2.35f
private const val MAX_CONSTRAINED_HEIGHT_TARGET_DELTA_METERS = 0.06f
private const val MIN_AUTHORITATIVE_DISTANCE_METERS = 0.35f
private const val MAX_AUTHORITATIVE_DISTANCE_METERS = 12.0f
private const val MAX_CONSTRAINED_DISTANCE_TARGET_DELTA_METERS = 0.35f
private const val MAX_CONSTRAINED_DISTANCE_TARGET_RATIO = 0.12f

