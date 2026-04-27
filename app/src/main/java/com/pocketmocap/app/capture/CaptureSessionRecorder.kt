package com.pocketmocap.app.capture

import android.content.Context
import android.os.Environment
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.tracking.METRIC_EVIDENCE_V2_OUTPUT_NAMES
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

