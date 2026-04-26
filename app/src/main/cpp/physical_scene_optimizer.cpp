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

bool valid_height(float value) {
    return finite(value) && value >= 1.15f && value <= 2.15f;
}

float valid_height_factor(float value) {
    return valid_height(value) ? value : std::numeric_limits<float>::quiet_NaN();
}

bool height_agrees_with_anchor(float candidate, const std::vector<float>& anchors, float max_delta) {
    if (!valid_height(candidate)) return false;
    bool has_anchor = false;
    for (float anchor : anchors) {
        if (!valid_height(anchor)) continue;
        has_anchor = true;
        if (std::fabs(candidate - anchor) <= max_delta) return true;
    }
    return !has_anchor;
}

bool hip_height_conflicts_with_raw_top(float hip_height, float raw_top, float torso_height, float raw_pixel) {
    if (!valid_height(hip_height) || !valid_height(raw_top)) return false;
    if (hip_height <= raw_top + MAX_HIP_RAW_TOP_STANDALONE_GAP) return false;
    const bool independent_support =
        (valid_height(torso_height) && std::fabs(torso_height - hip_height) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP) ||
        (valid_height(raw_pixel) && std::fabs(raw_pixel - hip_height) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP);
    return !independent_support;
}

float semantic_endpoint_bias_for_factors(
    float positive_bias,
    float raw_top,
    float raw_pixel,
    float hip_height,
    float torso_height,
    float previous_height
) {
    if (positive_bias <= 0.0f || !valid_height(raw_top)) return 0.0f;
    const float biased_top = raw_top + positive_bias;
    const bool independent_support =
        (valid_height(torso_height) && std::fabs(torso_height - biased_top) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP) ||
        (valid_height(raw_pixel) && std::fabs(raw_pixel - biased_top) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP);
    if (independent_support) return positive_bias;

    float max_anchor = std::numeric_limits<float>::quiet_NaN();
    if (valid_height(hip_height)) max_anchor = hip_height;
    if (valid_height(previous_height)) {
        max_anchor = valid_height(max_anchor) ? std::max(max_anchor, previous_height) : previous_height;
    }
    if (!valid_height(max_anchor)) return positive_bias;
    return raw_top >= max_anchor - MAX_TOP_LOW_BIAS_MASK_GAP ? positive_bias : 0.0f;
}

bool semantic_height_agreement(float hip_height, float torso_height, float top_height) {
    const bool has_torso = valid_height(torso_height);
    const bool has_hip = valid_height(hip_height);
    // Hip-geometry height is the output of a 2-ray LSQ that enforces the
    // hip-center:height anthropometric ratio (with a 0.18 ratio-error gate).
    // When it converges into the valid body-height range, is INDEPENDENT of
    // torso (not the same torso-ratio seed) and does not strongly conflict
    // with torso, it is itself multi-evidence and can stand alone — torso
    // has a known low bias and top-ray has a known high bias, so the
    // pairwise gate alone almost never engages on real captures.
    const bool hip_independent = has_hip && (!has_torso || std::fabs(hip_height - torso_height) > 0.015f);
    const bool hip_does_not_conflict = has_hip && (!has_torso || std::fabs(hip_height - torso_height) <= 0.22f);
    const bool has_top = valid_height(top_height);
    const bool hip_does_not_mask_low_top = has_hip &&
        (!has_top || hip_height <= top_height + MAX_HIP_RAW_TOP_STANDALONE_GAP);
    if (hip_independent && hip_does_not_conflict && hip_does_not_mask_low_top) {
        return true;
    }

    std::vector<float> values;
    if (has_torso) values.push_back(torso_height);
    if (has_hip && (!has_torso || std::fabs(hip_height - torso_height) > 0.015f)) values.push_back(hip_height);
    if (valid_height(top_height)) values.push_back(top_height);
    if (values.size() < 2) return false;
    for (size_t i = 0; i < values.size(); ++i) {
        for (size_t j = i + 1; j < values.size(); ++j) {
            if (std::fabs(values[i] - values[j]) <= 0.08f) return true;
        }
    }
    const auto bounds = std::minmax_element(values.begin(), values.end());
    return values.size() >= 3 && (*bounds.second - *bounds.first) <= 0.14f;
}

float robust_weighted_average(const std::vector<Factor>& factors, float fallback) {
    std::vector<float> values;
    values.reserve(factors.size());
    for (const auto& factor : factors) {
        if (finite(factor.value) && factor.weight > 0.0f) values.push_back(factor.value);
    }
    if (values.empty()) return fallback;
    std::sort(values.begin(), values.end());
    const float median = values[values.size() / 2];
    float total_weight = 0.0f;
    float weighted = 0.0f;
    for (const auto& factor : factors) {
        if (!finite(factor.value) || factor.weight <= 0.0f) continue;
        const float residual = std::fabs(factor.value - median);
        const float scaled = residual / 0.22f;
        const float robust_weight = factor.weight / (1.0f + scaled * scaled);
        weighted += factor.value * robust_weight;
        total_weight += robust_weight;
    }
    return total_weight > 1e-5f ? weighted / total_weight : median;
}

float spread(const std::vector<Factor>& factors) {
    bool has = false;
    float lo = 0.0f;
    float hi = 0.0f;
    for (const auto& factor : factors) {
        if (!finite(factor.value)) continue;
        if (!has) {
            lo = factor.value;
            hi = factor.value;
            has = true;
        } else {
            lo = std::min(lo, factor.value);
            hi = std::max(hi, factor.value);
        }
    }
    return has ? hi - lo : 0.0f;
}

float weighted_residual(const std::vector<Factor>& factors, float target) {
    if (!finite(target)) return 0.0f;
    float total = 0.0f;
    float weight = 0.0f;
    for (const auto& factor : factors) {
        if (!finite(factor.value)) continue;
        total += std::fabs(factor.value - target) * factor.weight;
        weight += factor.weight;
    }
    return weight > 1e-5f ? total / weight : 0.0f;
}

float stabilize_distance(float previous, float measured, float fallback, bool trusted, float factor_spread, float confidence) {
    if (!finite(measured) || measured < 0.35f || measured > 12.0f) return previous;
    if (!finite(previous)) {
        return (!trusted && finite(fallback) && fallback >= 0.70f && fallback <= 12.0f) ? fallback : measured;
    }
    if (!trusted) {
        if (!finite(fallback) || fallback < 0.70f || fallback > 12.0f) return previous;
        const float raw_delta = fallback - previous;
        const float max_step = std::fabs(raw_delta) > 0.75f ? 0.55f : 0.24f;
        const float alpha = std::fabs(raw_delta) > 0.75f ? 0.34f : 0.18f;
        const float delta = clamp(raw_delta, -max_step, max_step);
        return clamp(previous + delta * alpha, 0.35f, 12.0f);
    }
    float alpha = 0.055f;
    float max_step = 0.075f;
    if (factor_spread <= 0.45f && confidence >= 0.62f) {
        alpha = 0.24f;
        max_step = 0.22f;
    } else if (factor_spread <= 0.85f) {
        alpha = 0.14f;
        max_step = 0.14f;
    }
    const float delta = clamp(measured - previous, -max_step, max_step);
    return clamp(previous + delta * alpha, 0.35f, 12.0f);
}

float stabilize_height(float previous, float measured, bool trusted, float confidence) {
    if (!finite(measured) || measured < 1.15f || measured > 2.15f) {
        return finite(previous) ? previous : std::numeric_limits<float>::quiet_NaN();
    }
    if (!finite(previous)) return measured;
    if (!trusted) return previous;
    const float delta = measured - previous;
    float alpha = 0.004f;
    if (confidence < 0.55f) alpha = 0.015f;
    else if (std::fabs(delta) <= 0.025f) alpha = 0.10f;
    else if (std::fabs(delta) <= 0.075f) alpha = 0.045f;
    else if (std::fabs(delta) <= 0.16f) alpha = 0.018f;
    return clamp(previous + delta * alpha, 1.15f, 2.15f);
}

