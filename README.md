# Pocket Mocap Android (poandroid)

Pocket Mocap Android is the phone capture subsystem. It records camera-frame pose evidence, session metadata, and synchronized landmark packets for the PC reconstruction subsystem.

## System Role
- **Single phone mode:** one Android phone records 2D landmarks, camera metadata, scene evidence, and timestamps for one PC session.
- **Multi-phone mode:** multiple Android phones join the same PC session and send synchronized evidence streams for stronger 3D reconstruction.
- **Subsystem boundary:** `poandroid` owns capture and evidence transport; `popc` owns calibration, reconstruction, validation, replay, and artifact export.
- **ML stack:** both subsystems are organized around the seven-model evidence stack: pose landmarking, hand landmarking, face landmarking, object/person segmentation, depth/metric evidence, temporal motion filtering, and pose-lifter/canonical reconstruction support.

## Capabilities
The Android app runs phone-side capture, pose landmark detection, capture review, synchronization, evidence packaging, and transport. It supports single-phone capture and multi-phone capture where several Android devices join one PC session.

## Capture Evidence: Session 301076
Source session: `C:\Users\Asus\Documents\Pocap\sessions\301076`.
Source capture: `cap_20260609_145233_301076_003`.

Each pose subsection uses exactly three real capture-evidence views from the same pose family:
1. phone camera evidence;
2. phone 2D landmark evidence;
3. PC reconstructed 3D evidence.

### T pose

![T pose phone camera](docs/capture_evidence/T%20pose.jpg)
![T pose phone landmarks](docs/capture_evidence/t_pose_2d_landmarks.png)
![T pose PC reconstruction](docs/capture_evidence/T%20pose3d.png)

### A pose (back view)

![A pose (back view) phone camera](docs/capture_evidence/A%20pose.jpg)
![A pose (back view) phone landmarks](docs/capture_evidence/a_pose_2d_landmarks.png)
![A pose (back view) PC reconstruction](docs/capture_evidence/A%20pose3d.png)

### 45 deg left

![45 deg left phone camera](docs/capture_evidence/45deg_left.jpg)
![45 deg left phone landmarks](docs/capture_evidence/45_deg_left_2d_landmarks.png)
![45 deg left PC reconstruction](docs/capture_evidence/45deg_left3d.png)

### 45 deg right

![45 deg right phone camera](docs/capture_evidence/45deg_right.jpg)
![45 deg right phone landmarks](docs/capture_evidence/45_deg_right_2d_landmarks.png)
![45 deg right PC reconstruction](docs/capture_evidence/45deg_right3d.png)

### Handraise pose

![Handraise pose phone camera](docs/capture_evidence/Handraise_pose.jpg)
![Handraise pose phone landmarks](docs/capture_evidence/handraise_pose_2d_landmarks.png)
![Handraise pose PC reconstruction](docs/capture_evidence/Handraise_pose3d.png)

## Build
```powershell
cd D:\pocket-mocap\poandroid
.\gradlew.bat :app:assembleV2LabDebug
```

## Test
```powershell
cd D:\pocket-mocap\poandroid
.\gradlew.bat :app:testV2LabDebugUnitTest
```
