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

int factor_bits(const std::vector<Factor>& factors) {
    int bits = 0;
    for (const auto& factor : factors) {
        if (finite(factor.value) && factor.weight > 0.0f) bits |= factor.bit;
    }
    return bits;
}

#if POCKET_MOCAP_HAS_CERES
enum class ResidualKind {
    Distance,
    Height,
    CameraHeight,
    HipDepth,
    TopHeight,
    PixelHeight,
    Bias,
};

struct SceneResidual {
    ResidualKind kind;
    int variable;
    double target;
    double weight;

    template <typename T>
    bool operator()(const T* const state, T* residual) const {
        T prediction = T(0);
        switch (kind) {
            case ResidualKind::Distance:
                prediction = state[STATE_DISTANCE];
                break;
            case ResidualKind::Height:
                prediction = state[STATE_HEIGHT];
                break;
            case ResidualKind::CameraHeight:
                prediction = state[STATE_CAMERA_HEIGHT] - (T(target) + state[STATE_FLOOR_BIAS]);
                residual[0] = T(std::sqrt(weight)) * prediction;
                return true;
            case ResidualKind::HipDepth:
                prediction = state[STATE_DISTANCE] - (T(target) * state[STATE_DEPTH_SCALE] + state[STATE_DEPTH_OFFSET]);
                residual[0] = T(std::sqrt(weight)) * prediction;
                return true;
            case ResidualKind::TopHeight:
            case ResidualKind::PixelHeight:
                prediction = state[STATE_HEIGHT];
                break;
            case ResidualKind::Bias:
                prediction = state[variable];
                break;
        }
        residual[0] = T(std::sqrt(weight)) * (prediction - T(target));
        return true;
    }
};

void add_residual(
    ceres::Problem& problem,
    double* state,
    ResidualKind kind,
    int variable,
    double target,
    double weight,
    ceres::LossFunction* loss
) {
    if (!std::isfinite(target) || weight <= 0.0) return;
    auto* functor = new SceneResidual{kind, variable, target, weight};
    problem.AddResidualBlock(
        new ceres::AutoDiffCostFunction<SceneResidual, 1, STATE_SIZE>(functor),
        loss,
        state
    );
}

bool solve_with_ceres(
    const OptimizerInputs& input,
    const std::vector<Factor>& distance_factors,
    const std::vector<Factor>& height_factors,
    double* state,
    double* final_cost
) {
    ceres::Problem problem;
    problem.AddParameterBlock(state, STATE_SIZE);

    for (int i = 0; i < STATE_SIZE; ++i) {
        problem.SetParameterLowerBound(state, i, -10.0);
        problem.SetParameterUpperBound(state, i, 10.0);
    }
    problem.SetParameterLowerBound(state, STATE_DISTANCE, 0.35);
    problem.SetParameterUpperBound(state, STATE_DISTANCE, 12.0);
    problem.SetParameterLowerBound(state, STATE_HEIGHT, 1.15);
    problem.SetParameterUpperBound(state, STATE_HEIGHT, 2.15);
    problem.SetParameterLowerBound(state, STATE_CAMERA_HEIGHT, 0.20);
    problem.SetParameterUpperBound(state, STATE_CAMERA_HEIGHT, 2.50);
    problem.SetParameterLowerBound(state, STATE_FLOOR_BIAS, -0.20);
    problem.SetParameterUpperBound(state, STATE_FLOOR_BIAS, 0.20);
    problem.SetParameterLowerBound(state, STATE_DEPTH_SCALE, 0.92);
    problem.SetParameterUpperBound(state, STATE_DEPTH_SCALE, 1.08);
    problem.SetParameterLowerBound(state, STATE_DEPTH_OFFSET, -0.30);
    problem.SetParameterUpperBound(state, STATE_DEPTH_OFFSET, 0.30);
    problem.SetParameterLowerBound(state, STATE_ENDPOINT_BIAS, MIN_HEIGHT_ENDPOINT_BIAS);
    problem.SetParameterUpperBound(state, STATE_ENDPOINT_BIAS, MAX_HEIGHT_ENDPOINT_BIAS);

    ceres::LossFunction* distance_loss = new ceres::HuberLoss(0.35);
    ceres::LossFunction* soft_loss = new ceres::CauchyLoss(0.55);
    ceres::LossFunction* height_loss = new ceres::HuberLoss(0.16);

    for (const auto& factor : distance_factors) {
        add_residual(problem, state, ResidualKind::Distance, STATE_DISTANCE, factor.value, factor.weight, soft_loss);
    }
    for (const auto& factor : height_factors) {
        if (factor.bit == FACTOR_TOP) {
            add_residual(problem, state, ResidualKind::TopHeight, STATE_HEIGHT, factor.value, factor.weight * 1.10, height_loss);
        } else if (factor.bit == FACTOR_PIXEL) {
            add_residual(problem, state, ResidualKind::PixelHeight, STATE_HEIGHT, factor.value, factor.weight * 0.85, height_loss);
        } else {
            add_residual(problem, state, ResidualKind::Height, STATE_HEIGHT, factor.value, factor.weight, height_loss);
        }
    }

    add_residual(problem, state, ResidualKind::CameraHeight, STATE_CAMERA_HEIGHT, input.raw_camera_height, 0.80, new ceres::HuberLoss(0.12));
    if (finite(input.previous_distance)) {
        add_residual(problem, state, ResidualKind::Distance, STATE_DISTANCE, input.previous_distance, 0.36, new ceres::HuberLoss(0.45));
    }
    if (finite(input.previous_height)) {
        add_residual(problem, state, ResidualKind::Height, STATE_HEIGHT, input.previous_height, 3.80, new ceres::HuberLoss(0.035));
    }
    add_residual(problem, state, ResidualKind::Bias, STATE_FLOOR_BIAS, input.floor_bias, 5.0, new ceres::HuberLoss(0.035));
    add_residual(problem, state, ResidualKind::Bias, STATE_DEPTH_SCALE, input.depth_scale, 7.0, new ceres::HuberLoss(0.010));
    add_residual(problem, state, ResidualKind::Bias, STATE_DEPTH_OFFSET, input.depth_offset, 4.0, new ceres::HuberLoss(0.035));
    add_residual(problem, state, ResidualKind::Bias, STATE_ENDPOINT_BIAS, input.endpoint_bias, 4.5, new ceres::HuberLoss(0.025));

    ceres::Solver::Options options;
    options.max_num_iterations = 28;
    options.num_threads = 1;
    options.linear_solver_type = ceres::DENSE_QR;
    options.minimizer_progress_to_stdout = false;
    options.logging_type = ceres::SILENT;
    options.function_tolerance = 1e-6;
    options.gradient_tolerance = 1e-8;
    options.parameter_tolerance = 1e-6;

    ceres::Solver::Summary summary;
    ceres::Solve(options, &problem, &summary);
    if (final_cost != nullptr) *final_cost = summary.final_cost;
    return summary.IsSolutionUsable();
}
#else
bool solve_with_ceres(
    const OptimizerInputs&,
    const std::vector<Factor>&,
    const std::vector<Factor>&,
    double*,
    double*
) {
    return false;
}
#endif

OptimizerInputs read_inputs(JNIEnv* env, jfloatArray values) {
    constexpr int kInputSize = 23;
    jfloat in[kInputSize];
    const jsize input_len = env->GetArrayLength(values);
    for (int i = 0; i < kInputSize; ++i) in[i] = std::numeric_limits<float>::quiet_NaN();
    env->GetFloatArrayRegion(values, 0, std::min(input_len, static_cast<jsize>(kInputSize)), in);

    OptimizerInputs input;
    const bool versioned = input_len >= 22 && finite(in[0]) && (std::fabs(in[0] - 2.0f) < 0.01f || std::fabs(in[0] - 3.0f) < 0.01f);
    const bool has_relative_scale = input_len >= 23 && finite(in[0]) && std::fabs(in[0] - 3.0f) < 0.01f;
    const int o = versioned ? 1 : 0;
    input.confidence = in[o + 0];
    input.raw_distance = in[o + 1];
    input.raw_height = in[o + 2];
    input.raw_camera_height = in[o + 3];
    input.raw_hip = in[o + 4];
    input.foot = in[o + 5];
    input.roi = in[o + 6];
    input.top = in[o + 7];
    input.pixel = in[o + 8];
    if (versioned) {
        input.hip_geometry_distance = in[10];
        input.hip_geometry_height = in[11];
        input.torso_height = in[12];
        input.grounded_foot = in[13];
        input.body_scale_confidence = in[14];
        input.bone_length_spread = in[15];
        const int shift = has_relative_scale ? 1 : 0;
        if (has_relative_scale) input.relative_scale_distance = in[16];
        input.previous_distance = in[16 + shift];
        input.previous_height = in[17 + shift];
        input.floor_bias = finite(in[18 + shift]) ? clamp(in[18 + shift], -0.20f, 0.20f) : 0.0f;
        input.depth_scale = finite(in[19 + shift]) ? clamp(in[19 + shift], 0.92f, 1.08f) : 1.0f;
        input.depth_offset = finite(in[20 + shift]) ? clamp(in[20 + shift], -0.30f, 0.30f) : 0.0f;
        input.endpoint_bias = finite(in[21 + shift])
            ? clamp(in[21 + shift], MIN_HEIGHT_ENDPOINT_BIAS, MAX_HEIGHT_ENDPOINT_BIAS)
            : 0.0f;
    } else {
        input.previous_distance = in[9];
        input.previous_height = in[10];
        input.floor_bias = finite(in[11]) ? clamp(in[11], -0.20f, 0.20f) : 0.0f;
        input.depth_scale = finite(in[12]) ? clamp(in[12], 0.92f, 1.08f) : 1.0f;
        input.depth_offset = finite(in[13]) ? clamp(in[13], -0.30f, 0.30f) : 0.0f;
        input.endpoint_bias = finite(in[14])
            ? clamp(in[14], MIN_HEIGHT_ENDPOINT_BIAS, MAX_HEIGHT_ENDPOINT_BIAS)
            : 0.0f;
    }
    return input;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pocketmocap_app_ui_PhysicalSceneOptimizer_nativeOptimize(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray values
) {
    const OptimizerInputs input = read_inputs(env, values);

    const float corrected_hip = finite(input.raw_hip)
        ? clamp(input.raw_hip * input.depth_scale + input.depth_offset, 0.35f, 12.0f)
        : std::numeric_limits<float>::quiet_NaN();
    const bool foot_roi_strict_agreement = finite(input.foot) && finite(input.roi) &&
        strict_candidate_agreement(input.foot, input.roi, 0.48f, 0.16f);
    const float stable_non_hip = stable_non_hip_reference(
        input.foot,
        input.grounded_foot,
        input.roi,
        input.relative_scale_distance,
        input.previous_distance,
        foot_roi_strict_agreement
    );

    int flags = 0;
    if (finite(corrected_hip)) flags |= FLAG_REJECTED_HIP;

    float hip_reference = std::numeric_limits<float>::quiet_NaN();
    if (finite(input.previous_distance)) {
        float best_delta = std::numeric_limits<float>::infinity();
        const float refs[] = {
            input.roi,
            input.raw_distance,
            input.relative_scale_distance,
            input.previous_distance,
            input.foot,
        };
        for (float ref : refs) {
            if (!finite(ref)) continue;
            const float delta = std::fabs(ref - input.previous_distance);
            if (delta < best_delta) {
                best_delta = delta;
                hip_reference = ref;
            }
        }
    } else if (finite(input.roi)) {
        hip_reference = input.roi;
    } else if (finite(input.raw_distance)) {
        hip_reference = input.raw_distance;
    } else if (finite(input.relative_scale_distance)) {
        hip_reference = input.relative_scale_distance;
    } else if (finite(input.foot)) {
        hip_reference = input.foot;
    }
    const bool hip_geometry_rejected = finite(input.hip_geometry_distance) &&
        (
            (finite(stable_non_hip) &&
                !strict_candidate_agreement(input.hip_geometry_distance, stable_non_hip, 0.32f, 0.16f)) ||
            (finite(hip_reference) &&
                input.hip_geometry_distance > hip_reference &&
                !candidate_agreement(input.hip_geometry_distance, hip_reference) &&
                (finite(input.previous_distance) || finite(input.roi) || finite(input.relative_scale_distance) || finite(input.foot)))
        );
    if (hip_geometry_rejected) flags |= FLAG_REJECTED_HIP_GEOMETRY;
    const float usable_hip_geometry_distance = hip_geometry_rejected
        ? std::numeric_limits<float>::quiet_NaN()
        : input.hip_geometry_distance;
    const bool raw_distance_rejected = hip_geometry_rejected &&
        finite(input.raw_distance) &&
        finite(stable_non_hip) &&
        !strict_candidate_agreement(input.raw_distance, stable_non_hip, 0.36f, 0.18f);
    const float usable_raw_distance = raw_distance_rejected
        ? std::numeric_limits<float>::quiet_NaN()
        : input.raw_distance;
    const float foot_reference = finite(usable_hip_geometry_distance)
        ? usable_hip_geometry_distance
        : (finite(input.roi) ? input.roi : usable_raw_distance);
    const bool foot_roi_wild = finite(input.foot) && finite(foot_reference) &&
        !(finite(input.roi) ? foot_roi_strict_agreement : candidate_agreement(input.foot, foot_reference)) &&
        input.foot > foot_reference;

    const bool trusted_foot = finite(input.foot) && !foot_roi_wild &&
        ((finite(usable_hip_geometry_distance) && candidate_agreement(input.foot, usable_hip_geometry_distance)) ||
         (!finite(input.roi) && finite(usable_raw_distance) && candidate_agreement(input.foot, usable_raw_distance)) ||
         (finite(input.roi) && foot_roi_strict_agreement) ||
         (!finite(usable_raw_distance) && !finite(input.roi) && finite(input.grounded_foot)));
    if (finite(input.foot) && !trusted_foot) flags |= FLAG_REJECTED_FOOT;

    const float foot_roi_drift = (finite(input.foot) && finite(input.roi))
        ? std::fabs(input.foot - input.roi) / std::max(std::max(input.foot, input.roi), 1e-4f)
        : 0.0f;
    const bool foot_hip_strict_agreement = finite(input.foot) && finite(usable_hip_geometry_distance) &&
        std::fabs(input.foot - usable_hip_geometry_distance) <= 0.30f &&
        (std::fabs(input.foot - usable_hip_geometry_distance) / std::max(std::max(input.foot, usable_hip_geometry_distance), 1e-4f)) <= 0.12f;
    const float foot_weight = !trusted_foot ? 0.0f :
        finite(input.grounded_foot) && foot_hip_strict_agreement ? 0.22f :
        finite(input.grounded_foot) && foot_roi_strict_agreement ? 0.34f :
        foot_roi_strict_agreement ? 0.30f :
        finite(input.grounded_foot) ? 0.10f :
        finite(usable_hip_geometry_distance) && !candidate_agreement(input.foot, usable_hip_geometry_distance) ? 0.04f :
        finite(usable_raw_distance) && !candidate_agreement(input.foot, usable_raw_distance) ? 0.05f :
        finite(input.roi) && foot_roi_drift > 0.42f ? 0.06f :
        finite(input.roi) && foot_roi_drift > 0.25f ? 0.10f :
        finite(input.roi) ? 0.12f : 0.16f;
    const float hip_geometry_weight = !finite(input.hip_geometry_distance) ? 0.0f :
        hip_geometry_rejected ? 0.0f :
        input.confidence >= 0.70f ? 0.72f :
        input.confidence >= 0.55f ? 0.58f : 0.38f;
    const float roi_weight = !finite(input.roi) ? 0.0f :
        foot_roi_strict_agreement && !finite(usable_hip_geometry_distance) ? 0.24f :
        foot_roi_strict_agreement ? 0.16f :
        finite(usable_hip_geometry_distance) ? 0.020f :
        finite(usable_raw_distance) ? 0.030f :
        !trusted_foot && finite(input.foot) ? 0.10f :
        trusted_foot ? 0.045f : 0.12f;
    const float relative_weight = !finite(input.relative_scale_distance) ? 0.0f :
        foot_roi_strict_agreement ? 0.035f :
        !finite(usable_hip_geometry_distance) && !trusted_foot ? 0.18f :
        !finite(usable_hip_geometry_distance) ? 0.10f : 0.045f;
    const float raw_weight = raw_distance_rejected ? 0.0f :
        (foot_roi_strict_agreement && !finite(usable_hip_geometry_distance)) ? 0.06f :
        (flags & FLAG_REJECTED_FOOT) ? (finite(input.roi) ? 0.0f : 0.10f) :
        (finite(usable_hip_geometry_distance) ? 0.20f : 0.34f);

    std::vector<Factor> distance_factors;
    if (hip_geometry_weight > 0.0f) distance_factors.push_back({usable_hip_geometry_distance, hip_geometry_weight, FACTOR_HIP});
    if (trusted_foot && foot_weight > 0.0f) distance_factors.push_back({input.foot, foot_weight, FACTOR_FOOT});
    if (finite(usable_raw_distance) && raw_weight > 0.0f) distance_factors.push_back({usable_raw_distance, raw_weight, FACTOR_RAW_DISTANCE});
    if (finite(input.roi) && roi_weight > 0.0f) distance_factors.push_back({input.roi, roi_weight, FACTOR_ROI});
    if (finite(input.relative_scale_distance) && relative_weight > 0.0f) distance_factors.push_back({input.relative_scale_distance, relative_weight, FACTOR_RELATIVE_SCALE});

    std::vector<Factor> raw_distance_factors;
    if (finite(input.hip_geometry_distance)) raw_distance_factors.push_back({input.hip_geometry_distance, 0.52f, FACTOR_HIP});
    if (finite(input.foot)) raw_distance_factors.push_back({input.foot, 0.16f, FACTOR_FOOT});
    if (finite(input.raw_distance)) raw_distance_factors.push_back({input.raw_distance, 0.20f, FACTOR_RAW_DISTANCE});
    if (finite(input.roi)) raw_distance_factors.push_back({input.roi, 0.04f, FACTOR_ROI});
    if (finite(input.relative_scale_distance)) raw_distance_factors.push_back({input.relative_scale_distance, 0.10f, FACTOR_RELATIVE_SCALE});

    const float robust_distance = robust_weighted_average(
        distance_factors,
        finite(usable_raw_distance) ? usable_raw_distance : input.previous_distance
    );

    const float previous_height = valid_height_factor(input.previous_height);
    const float raw_height = valid_height_factor(input.raw_height);
    const float raw_hip_height = valid_height_factor(input.hip_geometry_height);
    const float torso_height = valid_height_factor(input.torso_height);
    const float raw_top_height = valid_height_factor(input.top);
    const float raw_pixel_height = valid_height_factor(input.pixel);
    const float hip_height = hip_height_conflicts_with_raw_top(
        raw_hip_height,
        raw_top_height,
        torso_height,
        raw_pixel_height
    ) ? std::numeric_limits<float>::quiet_NaN() : raw_hip_height;
    std::vector<float> height_anchors;
    if (finite(hip_height)) height_anchors.push_back(hip_height);
    if (finite(torso_height)) height_anchors.push_back(torso_height);
    if (finite(previous_height)) height_anchors.push_back(previous_height);
    const float semantic_endpoint_bias = semantic_endpoint_bias_for_factors(
        std::max(input.endpoint_bias, 0.0f),
        raw_top_height,
        raw_pixel_height,
        hip_height,
        torso_height,
        previous_height
    );
    const float biased_top_height = input.top + semantic_endpoint_bias;
    const float top_height = height_agrees_with_anchor(biased_top_height, height_anchors, 0.18f)
        ? biased_top_height
        : std::numeric_limits<float>::quiet_NaN();
    const float biased_pixel_height = input.pixel + semantic_endpoint_bias;
    const float pixel_height = height_agrees_with_anchor(biased_pixel_height, height_anchors, 0.22f)
        ? biased_pixel_height
        : std::numeric_limits<float>::quiet_NaN();

    std::vector<Factor> height_factors;
    const bool hip_height_duplicates_torso = finite(hip_height) &&
        finite(torso_height) &&
        std::fabs(hip_height - torso_height) <= 0.015f;
    if (finite(hip_height) && !hip_height_duplicates_torso) {
        height_factors.push_back({hip_height, 0.34f, FACTOR_RAW_HEIGHT});
    }
    if (finite(top_height)) height_factors.push_back({top_height, 0.32f, FACTOR_TOP});
    if (finite(torso_height)) height_factors.push_back({torso_height, 0.10f, FACTOR_TORSO});
    if (finite(raw_height)) height_factors.push_back({raw_height, 0.08f, FACTOR_RAW_HEIGHT});
    if (finite(pixel_height)) height_factors.push_back({pixel_height, 0.035f, FACTOR_PIXEL});
    const bool has_height_evidence = !height_factors.empty() || finite(previous_height);

    const float robust_height = robust_weighted_average(
        height_factors,
        finite(raw_height) ? raw_height : previous_height
    );

    double state[STATE_SIZE] = {
        static_cast<double>(finite(robust_distance) ? robust_distance : 2.5f),
        static_cast<double>(finite(robust_height) ? robust_height : (finite(previous_height) ? previous_height : 1.65f)),
        static_cast<double>(finite(input.raw_camera_height) ? input.raw_camera_height + input.floor_bias : 0.68f),
        0.0,
        input.floor_bias,
        input.depth_scale,
        input.depth_offset,
        input.endpoint_bias,
    };
    double final_cost = 0.0;
    const bool ceres_ok = solve_with_ceres(input, distance_factors, height_factors, state, &final_cost);

    const float measured_distance = ceres_ok
        ? static_cast<float>(state[STATE_DISTANCE])
        : robust_distance;
    const float measured_height = has_height_evidence
        ? (ceres_ok ? static_cast<float>(state[STATE_HEIGHT]) : robust_height)
        : std::numeric_limits<float>::quiet_NaN();

    const float distance_spread = spread(raw_distance_factors);
    const bool stable_foot_roi_control = foot_roi_strict_agreement && !finite(usable_hip_geometry_distance);
    const float distance_control_spread = stable_foot_roi_control ? spread(distance_factors) : distance_spread;
    const float distance_residual = stable_foot_roi_control
        ? weighted_residual(distance_factors, measured_distance)
        : weighted_residual(raw_distance_factors, measured_distance);
    const bool distance_trusted = distance_control_spread <= 1.20f &&
        distance_residual <= 0.75f;
    if (distance_trusted) flags |= FLAG_DISTANCE_TRUSTED;
    const float fallback_distance = finite(usable_hip_geometry_distance) &&
            usable_hip_geometry_distance >= 0.35f &&
            usable_hip_geometry_distance <= 12.0f &&
            (!finite(input.roi) || candidate_agreement(usable_hip_geometry_distance, input.roi)) ? usable_hip_geometry_distance :
        trusted_foot && finite(input.foot) && input.foot >= 0.35f && input.foot <= 12.0f ? input.foot :
        finite(input.roi) ? input.roi :
        finite(input.relative_scale_distance) ? input.relative_scale_distance :
        finite(usable_raw_distance) ? usable_raw_distance :
        std::numeric_limits<float>::quiet_NaN();
    const float corrected_distance = stabilize_distance(
        input.previous_distance,
        measured_distance,
        fallback_distance,
        distance_trusted,
        distance_control_spread,
        input.confidence
    );

