#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>

#if POCKET_MOCAP_HAS_CERES
#include "ceres/ceres.h"
#endif

namespace {

constexpr int FLAG_DISTANCE_TRUSTED = 1;
constexpr int FLAG_HEIGHT_TRUSTED = 2;
constexpr int FLAG_REJECTED_HIP = 4;
constexpr int FLAG_REJECTED_FOOT = 8;
constexpr int FLAG_REJECTED_HIP_GEOMETRY = 16;

constexpr int FACTOR_HIP = 1;
constexpr int FACTOR_FOOT = 2;
constexpr int FACTOR_RAW_DISTANCE = 4;
constexpr int FACTOR_ROI = 8;
constexpr int FACTOR_TOP = 16;
constexpr int FACTOR_RAW_HEIGHT = 32;
constexpr int FACTOR_PIXEL = 64;
constexpr int FACTOR_NATIVE_FALLBACK = 128;
constexpr int FACTOR_NATIVE_CERES = 256;
constexpr int FACTOR_TORSO = 512;
constexpr int FACTOR_GROUNDED_FOOT = 1024;
constexpr int FACTOR_BONE = 2048;
constexpr int FACTOR_RELATIVE_SCALE = 4096;

constexpr int STATE_DISTANCE = 0;
constexpr int STATE_HEIGHT = 1;
constexpr int STATE_CAMERA_HEIGHT = 2;
constexpr int STATE_LATERAL = 3;
constexpr int STATE_FLOOR_BIAS = 4;
constexpr int STATE_DEPTH_SCALE = 5;
constexpr int STATE_DEPTH_OFFSET = 6;
constexpr int STATE_ENDPOINT_BIAS = 7;
constexpr int STATE_SIZE = 8;

constexpr float MIN_HEIGHT_ENDPOINT_BIAS = -0.025f;
constexpr float MAX_HEIGHT_ENDPOINT_BIAS = 0.080f;
constexpr float MAX_HIP_RAW_TOP_STANDALONE_GAP = 0.100f;
constexpr float MAX_TOP_LOW_BIAS_MASK_GAP = 0.045f;
constexpr float MAX_ENDPOINT_BIAS_SUPPORT_GAP = 0.110f;

struct Factor {
    float value;
    float weight;
    int bit;
};

struct OptimizerInputs {
    float confidence = std::numeric_limits<float>::quiet_NaN();
    float raw_distance = std::numeric_limits<float>::quiet_NaN();
    float raw_height = std::numeric_limits<float>::quiet_NaN();
    float raw_camera_height = std::numeric_limits<float>::quiet_NaN();
    float raw_hip = std::numeric_limits<float>::quiet_NaN();
    float foot = std::numeric_limits<float>::quiet_NaN();
    float roi = std::numeric_limits<float>::quiet_NaN();
    float top = std::numeric_limits<float>::quiet_NaN();
    float pixel = std::numeric_limits<float>::quiet_NaN();
    float hip_geometry_distance = std::numeric_limits<float>::quiet_NaN();
    float hip_geometry_height = std::numeric_limits<float>::quiet_NaN();
    float torso_height = std::numeric_limits<float>::quiet_NaN();
    float grounded_foot = std::numeric_limits<float>::quiet_NaN();
    float body_scale_confidence = std::numeric_limits<float>::quiet_NaN();
    float bone_length_spread = std::numeric_limits<float>::quiet_NaN();
    float relative_scale_distance = std::numeric_limits<float>::quiet_NaN();
    float previous_distance = std::numeric_limits<float>::quiet_NaN();
    float previous_height = std::numeric_limits<float>::quiet_NaN();
    float floor_bias = 0.0f;
    float depth_scale = 1.0f;
    float depth_offset = 0.0f;
    float endpoint_bias = 0.0f;
};

bool finite(float v) {
    return std::isfinite(v);
}

float clamp(float value, float lo, float hi) {
    return std::max(lo, std::min(value, hi));
}

bool candidate_agreement(float a, float b) {
    if (!finite(a) || !finite(b)) return false;
    const float abs_delta = std::fabs(a - b);
    const float ratio = abs_delta / std::max(std::max(a, b), 1e-4f);
    return abs_delta <= 1.25f || ratio <= 0.35f;
}

bool strict_candidate_agreement(float a, float b, float max_abs_delta, float max_ratio) {
    if (!finite(a) || !finite(b)) return false;
    const float abs_delta = std::fabs(a - b);
    const float ratio = abs_delta / std::max(std::max(a, b), 1e-4f);
    return abs_delta <= max_abs_delta && ratio <= max_ratio;
}

float weighted_pair(float a, float a_weight, float b, float b_weight) {
    return (a * a_weight + b * b_weight) / (a_weight + b_weight);
}

float stable_non_hip_reference(
    float foot,
    float grounded_foot,
    float roi,
    float relative_scale,
    float previous,
    bool foot_roi_strict_agreement
) {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float foot_roi_reference = finite(foot) && finite(roi) && foot_roi_strict_agreement
        ? weighted_pair(foot, 0.58f, roi, 0.42f)
        : nan;
    const float relative_temporal_reference =
        finite(relative_scale) &&
            finite(previous) &&
            strict_candidate_agreement(relative_scale, previous, 0.36f, 0.16f)
        ? weighted_pair(relative_scale, 0.72f, previous, 0.28f)
        : nan;
    if (finite(foot_roi_reference) && finite(relative_temporal_reference)) {
        return strict_candidate_agreement(foot_roi_reference, relative_temporal_reference, 0.45f, 0.18f)
            ? weighted_pair(relative_temporal_reference, 0.58f, foot_roi_reference, 0.42f)
            : foot_roi_reference;
    }
    if (finite(relative_temporal_reference)) return relative_temporal_reference;
    if (finite(foot_roi_reference)) return foot_roi_reference;
    if (
        finite(relative_scale) &&
        finite(foot) &&
        strict_candidate_agreement(relative_scale, foot, 0.42f, 0.18f)
    ) {
        return weighted_pair(relative_scale, 0.62f, foot, 0.38f);
    }
    if (
        finite(relative_scale) &&
        finite(roi) &&
        strict_candidate_agreement(relative_scale, roi, 0.42f, 0.18f)
    ) {
        return weighted_pair(relative_scale, 0.62f, roi, 0.38f);
    }
    if (
        finite(previous) &&
        finite(foot) &&
        strict_candidate_agreement(previous, foot, 0.55f, 0.22f)
    ) {
        return weighted_pair(previous, 0.54f, foot, 0.46f);
    }
    if (
        finite(previous) &&
        finite(roi) &&
        strict_candidate_agreement(previous, roi, 0.55f, 0.22f)
    ) {
        return weighted_pair(previous, 0.54f, roi, 0.46f);
    }
    if (
        finite(previous) &&
        finite(grounded_foot) &&
        strict_candidate_agreement(previous, grounded_foot, 0.55f, 0.22f)
    ) {
        return weighted_pair(previous, 0.54f, grounded_foot, 0.46f);
    }
    return nan;
}
