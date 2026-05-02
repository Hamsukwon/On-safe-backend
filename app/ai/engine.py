"""
AI 낙상 감지 엔진 — onsafeai.py 로직 기반
MediaPipe Pose → 피처 계산 → Savitzky-Golay 필터 → ML 추론
"""
import asyncio
from collections import defaultdict, deque
from pathlib import Path

import cv2
import joblib
import mediapipe as mp
import numpy as np
import pandas as pd
from scipy.signal import savgol_filter

_PKL_DIR = Path(__file__).parent.parent.parent / "pkl"
_WINDOW_SIZE = 5
_POLY_ORDER = 2

_joint_triplets = [
    ('neck', 0, 11, 12), ('shoulder_balance', 11, 0, 12),
    ('shoulder_left', 23, 11, 13), ('shoulder_right', 24, 12, 14),
    ('elbow_left', 11, 13, 15), ('elbow_right', 12, 14, 16),
    ('hip_left', 11, 23, 25), ('hip_right', 12, 24, 26),
    ('knee_left', 23, 25, 27), ('knee_right', 24, 26, 28),
    ('ankle_left', 25, 27, 31), ('ankle_right', 26, 28, 32),
    ('torso_left', 0, 11, 23), ('torso_right', 0, 12, 24),
    ('spine', 0, 23, 24),
]

# ── 싱글턴 모델 ──────────────────────────────────────────────────────────────

_model = None
_scaler = None
_pose = None


def _load_models() -> None:
    global _model, _scaler, _pose
    model_path = _PKL_DIR / "decision_tree_model.pkl"
    scaler_path = _PKL_DIR / "scaler.pkl"
    if model_path.exists() and scaler_path.exists():
        _model = joblib.load(model_path)
        _scaler = joblib.load(scaler_path)
    _pose = mp.solutions.pose.Pose(
        static_image_mode=True,
        model_complexity=1,
        enable_segmentation=False,
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5,
    )


# ── 기기별 상태 (Savitzky-Golay 버퍼 + 이전 프레임 값) ──────────────────────

_device_state: dict[str, dict] = {}


def _get_state(device_id: str) -> dict:
    if device_id not in _device_state:
        _device_state[device_id] = {
            "kp_buf": {
                f"kp{i}_{ax}": deque(maxlen=_WINDOW_SIZE)
                for i in range(33)
                for ax in ("x", "y", "z")
            },
            "prev_angles": {},
            "prev_ang_vel": {},
            "prev_center": None,
            "prev_center_speed": 0.0,
        }
    return _device_state[device_id]


# ── 수학 헬퍼 ─────────────────────────────────────────────────────────────────

def _compute_angle(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    ba, bc = a - b, c - b
    cos = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-8)
    return float(np.degrees(np.arccos(np.clip(cos, -1.0, 1.0))))


def _savgol_smooth(row: dict, kp_buf: dict) -> dict:
    out = row.copy()
    for key, buf in kp_buf.items():
        if key in row:
            buf.append(row[key])
            if len(buf) == _WINDOW_SIZE:
                out[key] = float(savgol_filter(np.array(buf), _WINDOW_SIZE, _POLY_ORDER)[-1])
    return out


def _centralize(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    px = (df["kp23_x"] + df["kp24_x"]) / 2
    py = (df["kp23_y"] + df["kp24_y"]) / 2
    pz = (df["kp23_z"] + df["kp24_z"]) / 2
    for col in df.columns:
        if col.endswith("_x"):
            df[col] -= px
        elif col.endswith("_y"):
            df[col] -= py
        elif col.endswith("_z"):
            df[col] -= pz
    return df


def _scale_normalize(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    scale = np.sqrt(
        (df["kp23_x"] - df["kp24_x"]) ** 2
        + (df["kp23_y"] - df["kp24_y"]) ** 2
        + (df["kp23_z"] - df["kp24_z"]) ** 2
    ).replace(0, 1)
    for col in df.columns:
        if any(s in col for s in ("_x", "_y", "_z")):
            df[col] /= scale
    return df


def _calculate_angles(row: dict, state: dict, fps: float = 30.0) -> dict:
    result = {}
    for j_name, a_idx, b_idx, c_idx in _joint_triplets:
        try:
            pts = [
                np.array([row[f"kp{i}_x"], row[f"kp{i}_y"], row[f"kp{i}_z"]])
                for i in (a_idx, b_idx, c_idx)
            ]
            angle = _compute_angle(*pts)
            ang_vel = (angle - state["prev_angles"].get(f"{j_name}_angle", angle)) * fps
            ang_acc = (ang_vel - state["prev_ang_vel"].get(f"{j_name}_angular_velocity", ang_vel)) * fps
            result[f"{j_name}_angle"] = angle
            result[f"{j_name}_angular_velocity"] = ang_vel
            result[f"{j_name}_angular_acceleration"] = ang_acc
            state["prev_angles"][f"{j_name}_angle"] = angle
            state["prev_ang_vel"][f"{j_name}_angular_velocity"] = ang_vel
        except KeyError:
            result[f"{j_name}_angle"] = 0.0
            result[f"{j_name}_angular_velocity"] = 0.0
            result[f"{j_name}_angular_acceleration"] = 0.0
    return result


def _center_dynamics(row: dict, state: dict, fps: float = 30.0) -> dict:
    center = np.array([
        (row.get("kp23_x", 0) + row.get("kp24_x", 0)) / 2,
        (row.get("kp23_y", 0) + row.get("kp24_y", 0)) / 2,
        (row.get("kp23_z", 0) + row.get("kp24_z", 0)) / 2,
    ])
    prev_c = state["prev_center"]
    prev_s = state["prev_center_speed"]
    disp = speed = acc = vel_change = 0.0
    if prev_c is not None:
        disp = float(np.linalg.norm(center - prev_c))
        speed = disp * fps
        acc = (speed - prev_s) * fps
        vel_change = abs(speed - prev_s)
    state["prev_center"] = center
    state["prev_center_speed"] = speed
    return {
        "center_displacement": disp,
        "center_speed": speed,
        "center_acceleration": acc,
        "center_velocity_change": vel_change,
        "center_mean_speed": speed,
        "center_mean_acceleration": acc,
    }


# ── 핵심 추론 함수 (동기, 스레드 풀에서 실행) ─────────────────────────────────

def infer_frame(jpeg_bytes: bytes, device_id: str, fps: float = 30.0) -> dict:
    """
    JPEG bytes → {"score": float 0-1, "fall": bool}
    모델 미로드 시 {"score": 0.0, "fall": False} 반환.
    """
    if _pose is None:
        _load_models()

    arr = np.frombuffer(jpeg_bytes, np.uint8)
    frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if frame is None:
        return {"score": 0.0, "fall": False}

    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    res = _pose.process(rgb)
    if not res.pose_landmarks:
        return {"score": 0.0, "fall": False}

    state = _get_state(device_id)
    raw = {f"kp{i}_{ax}": getattr(lm, ax)
           for i, lm in enumerate(res.pose_landmarks.landmark)
           for ax in ("x", "y", "z")}

    smoothed = _savgol_smooth(raw, state["kp_buf"])
    df = _scale_normalize(_centralize(pd.DataFrame([smoothed])))
    row = df.iloc[0].to_dict()

    feats = _calculate_angles(row, state, fps)
    feats.update(_center_dynamics(row, state, fps))

    if _model is None or _scaler is None:
        return {"score": 0.0, "fall": False}

    try:
        feat_cols = [c for c in feats if any(x in c for x in ("angle", "center"))]
        X = pd.DataFrame([feats])[feat_cols].reindex(
            columns=_scaler.feature_names_in_, fill_value=0.0
        )
        X_scaled = _scaler.transform(X)
        score = float(_model.predict_proba(X_scaled)[0][1] * 100)
        fall = bool(_model.predict(X_scaled)[0] == 1)
        return {"score": score, "fall": fall, "features": feats}
    except Exception as e:
        print(f"[ai.engine] 추론 오류: {e}")
        return {"score": 0.0, "fall": False, "features": {}}


async def infer_frame_async(jpeg_bytes: bytes, device_id: str, fps: float = 2.0) -> dict:
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, infer_frame, jpeg_bytes, device_id, fps)
