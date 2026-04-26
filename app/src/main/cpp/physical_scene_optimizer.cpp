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

