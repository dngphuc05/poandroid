package com.pocketmocap.app.pipeline

import kotlin.math.sqrt

/**
 * Simple neutral-template fallback for partially-occluded MediaPipe poses.
 *
 * Workflow:
 * 1. The client runs smoothing / Kalman / simple bone constraints first.
 * 2. If some joints are still effectively missing, we fill those joints from a
 *    neutral default 33-point body template.
 * 3. The completed 33-point pose is what the UI renders and what we send to the server.
 *
 * This engine intentionally stays simple:
 * - no opposite-side mirroring
 * - no learned per-user template
 * - short torso-relative holds for major joints
 * - wrist/ankle-local holds for finger/toe detail so they stay attached
 *
 * Hidden joints first try to stay near their most recent believable local pose.
 * If the torso has turned too much or the occlusion lasts too long, we fall back
 * to the neutral droop-down template aligned to the current torso.
 */
class LandmarkFallbackEngine {

