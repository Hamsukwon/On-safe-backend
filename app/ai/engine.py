"""
AI 낙상 감지 엔진 — OnSafe/ai-server/main.py 파이프라인 기반
landmark JSON → 30프레임 슬라이딩 윈도우 → XGBoost 추론

학습 파이프라인 대응:
  Step2 → _step2_resolve_nan()       (4.Nan_Resolution.ipynb)
  Step3 → _step3_smoothing_savgol()  (5.Smoothing_SGV.ipynb)
  Step4 → _step4_pose_normalize()    (6.Scaling.ipynb)
  Step5 → _step5_make_features()     (7.Make_Feature.ipynb)
  Step6 → _step6_scale()             (Make_AI.ipynb)
"""
import asyncio
from collections import deque
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from scipy.signal import savgol_filter

# ── 경로 상수 ──────────────────────────────────────────────────────────────────
_PKL_DIR = Path(__file__).parent.parent.parent / "pkl"

# ── 추론 파라미터 ──────────────────────────────────────────────────────────────
WINDOW_SIZE        = 30    # 슬라이딩 윈도우 프레임 수
STRIDE             = 5     # 추론 호출 간격 (프레임)
WARNING_THRESHOLD  = 51.0  # 주의
CRITICAL_THRESHOLD = 76.0  # 위험

# ── 관절 트리플 / 피처 순서 (학습 파이프라인과 1:1 동일) ──────────────────────
_JOINT_TRIPLETS = [
    ('neck',             0, 11, 12),
    ('shoulder_balance', 11,  0, 12),
    ('shoulder_left',   23, 11, 13),
    ('shoulder_right',  24, 12, 14),
    ('elbow_left',      11, 13, 15),
    ('elbow_right',     12, 14, 16),
    ('hip_left',        11, 23, 25),
    ('hip_right',       12, 24, 26),
    ('knee_left',       23, 25, 27),
    ('knee_right',      24, 26, 28),
    ('ankle_left',      25, 27, 31),
    ('ankle_right',     26, 28, 32),
    ('torso_left',       0, 11, 23),
    ('torso_right',      0, 12, 24),
    ('spine',            0, 23, 24),
]
_JOINTS_ORDER = [
    'neck', 'shoulder_balance',
    'shoulder_left', 'shoulder_right',
    'elbow_left', 'elbow_right',
    'hip_left', 'hip_right',
    'knee_left', 'knee_right',
    'torso_left', 'torso_right', 'spine',  # ankle보다 앞
    'ankle_left', 'ankle_right',
]
FEATURE_COLUMNS: list[str] = []
for _j in _JOINTS_ORDER:
    FEATURE_COLUMNS += [
        f'{_j}_angle',
        f'{_j}_angular_velocity',
        f'{_j}_angular_acceleration',
    ]
FEATURE_COLUMNS += ['center_distance', 'center_speed']
assert len(FEATURE_COLUMNS) == 47, f"Feature 개수 불일치: {len(FEATURE_COLUMNS)}"

# ── 싱글턴 모델 ────────────────────────────────────────────────────────────────
_model  = None
_scaler = None


def _load_models() -> None:
    global _model, _scaler
    model_path  = _PKL_DIR / "xgb_model.pkl"
    scaler_path = _PKL_DIR / "scaler.pkl"
    if not model_path.exists() or not scaler_path.exists():
        raise RuntimeError(f"모델/스케일러 파일 없음: {model_path}, {scaler_path}")
    _model  = joblib.load(model_path)
    _scaler = joblib.load(scaler_path)


# ── 기기별 프레임 버퍼 (Method A — deque 단일 책임) ───────────────────────────
_frame_buffers: dict[str, deque] = {}
_frame_counts:  dict[str, int]   = {}


def _get_buffer(device_id: str) -> deque:
    if device_id not in _frame_buffers:
        _frame_buffers[device_id] = deque(maxlen=WINDOW_SIZE)
    return _frame_buffers[device_id]


# ── 전처리 Step2 ───────────────────────────────────────────────────────────────

def _step2_resolve_nan(df: pd.DataFrame, conf_threshold: float = 0.3) -> pd.DataFrame:
    """visibility 기반 NaN 처리 → 3σ 이상치 제거 → 양방향 보간"""
    df    = df.copy()
    kp_x  = sorted([c for c in df.columns if c.endswith('_x')])
    kp_y  = sorted([c for c in df.columns if c.endswith('_y')])
    kp_z  = sorted([c for c in df.columns if c.endswith('_z')])
    confs = sorted([c for c in df.columns if c.endswith('_visibility')])

    n_frames, n_joints = len(df), len(kp_x)
    kp   = np.zeros((n_frames, n_joints, 3))
    conf = np.zeros((n_frames, n_joints))
    for j in range(n_joints):
        kp[:, j, 0] = df[kp_x[j]]
        kp[:, j, 1] = df[kp_y[j]]
        kp[:, j, 2] = df[kp_z[j]]
        conf[:, j]  = df[confs[j]]

    kp[conf < conf_threshold] = np.nan

    mean    = np.nanmean(kp, axis=(0, 1))
    std     = np.nanstd(kp, axis=(0, 1))
    outlier = (kp < mean - 3 * std) | (kp > mean + 3 * std)
    kp[outlier] = np.nan

    for f in range(n_frames):
        for j in range(n_joints):
            if np.isnan(kp[f, j, 0]):
                prev_val, next_val = None, None
                for p in range(f - 1, -1, -1):
                    if not np.isnan(kp[p, j, 0]):
                        prev_val = kp[p, j, :]; break
                for q in range(f + 1, n_frames):
                    if not np.isnan(kp[q, j, 0]):
                        next_val = kp[q, j, :]; break
                if prev_val is not None and next_val is not None:
                    kp[f, j, :] = (prev_val + next_val) / 2
                elif prev_val is not None:
                    kp[f, j, :] = prev_val
                elif next_val is not None:
                    kp[f, j, :] = next_val

    for j in range(n_joints):
        df[kp_x[j]] = kp[:, j, 0]
        df[kp_y[j]] = kp[:, j, 1]
        df[kp_z[j]] = kp[:, j, 2]

    num_cols = df.select_dtypes(include='number').columns
    for c in num_cols:
        df[c] = df[c].interpolate(method='cubic', limit_direction='both').ffill().bfill()
    return df


# ── 전처리 Step3 ───────────────────────────────────────────────────────────────

def _step3_smoothing_savgol(df: pd.DataFrame, window: int = 7, polyorder: int = 2) -> pd.DataFrame:
    """윈도우 전체에 Savitzky-Golay 스무딩 적용"""
    df         = df.copy()
    coord_cols = [c for c in df.columns if c.endswith(('_x', '_y', '_z'))]
    for col in coord_cols:
        arr = df[col].to_numpy()
        if len(arr) >= window:
            df[col] = savgol_filter(arr, window_length=window, polyorder=polyorder, mode='interp')
    return df


# ── 전처리 Step4 ───────────────────────────────────────────────────────────────

def _step4_pose_normalize(df: pd.DataFrame, pelvis: tuple = (23, 24)) -> pd.DataFrame:
    """골반 중앙정렬 + 두 골반 간 거리로 정규화"""
    df  = df.copy()
    px  = (df[f'kp{pelvis[0]}_x'] + df[f'kp{pelvis[1]}_x']) / 2
    py  = (df[f'kp{pelvis[0]}_y'] + df[f'kp{pelvis[1]}_y']) / 2
    pz  = (df[f'kp{pelvis[0]}_z'] + df[f'kp{pelvis[1]}_z']) / 2

    kp_x = [c for c in df.columns if c.endswith('_x')]
    kp_y = [c for c in df.columns if c.endswith('_y')]
    kp_z = [c for c in df.columns if c.endswith('_z')]
    for cx, cy, cz in zip(kp_x, kp_y, kp_z):
        df[cx] -= px
        df[cy] -= py
        df[cz] -= pz

    lx = df[f'kp{pelvis[0]}_x']; ly = df[f'kp{pelvis[0]}_y']; lz = df[f'kp{pelvis[0]}_z']
    rx = df[f'kp{pelvis[1]}_x']; ry = df[f'kp{pelvis[1]}_y']; rz = df[f'kp{pelvis[1]}_z']
    scale = np.sqrt((lx - rx) ** 2 + (ly - ry) ** 2 + (lz - rz) ** 2).replace(0, 1)
    for cx, cy, cz in zip(kp_x, kp_y, kp_z):
        df[cx] /= scale
        df[cy] /= scale
        df[cz] /= scale
    return df


# ── 전처리 Step5 헬퍼 ──────────────────────────────────────────────────────────

def _compute_dt(timestamps: np.ndarray) -> np.ndarray:
    n  = len(timestamps)
    dt = np.zeros_like(timestamps, dtype=float)
    if n >= 3:
        dt[1:-1] = (timestamps[2:] - timestamps[:-2]) / 2.0
    if n >= 2:
        dt[0]  = timestamps[1] - timestamps[0]
        dt[-1] = timestamps[-1] - timestamps[-2]
    return np.where(dt == 0, 1e-6, dt)


def _calc_angle(a_idx: int, b_idx: int, c_idx: int, df: pd.DataFrame) -> np.ndarray:
    """arctan2 기반 관절 각도 계산 (arccos 대비 수치 안정적)"""
    a  = df[[f'kp{a_idx}_x', f'kp{a_idx}_y', f'kp{a_idx}_z']].values
    b  = df[[f'kp{b_idx}_x', f'kp{b_idx}_y', f'kp{b_idx}_z']].values
    c  = df[[f'kp{c_idx}_x', f'kp{c_idx}_y', f'kp{c_idx}_z']].values
    ba = a - b; bc = c - b
    dot   = np.einsum('ij,ij->i', ba, bc)
    cross = np.linalg.norm(np.cross(ba, bc), axis=1)
    eps   = 1e-6
    dot   = np.where(np.abs(dot)   < eps, eps, dot)
    cross = np.where(np.abs(cross) < eps, eps, cross)
    return np.degrees(np.arctan2(cross, dot))


def _central_diff(series: np.ndarray, dt: np.ndarray, to_radian: bool = False) -> np.ndarray:
    """실제 timestamp 기반 중앙차분"""
    x   = np.radians(series) if to_radian else series.astype(float)
    out = np.zeros_like(x)
    if len(x) > 2:
        out[1:-1] = (x[2:] - x[:-2]) / (2 * dt[1:-1])
    out = np.nan_to_num(out, nan=0.0, posinf=0.0, neginf=0.0)
    return np.degrees(out) if to_radian else out


# ── 전처리 Step5 ───────────────────────────────────────────────────────────────

def _step5_make_features(df: pd.DataFrame) -> pd.DataFrame:
    """각도/각속도/각가속도(15관절) + center_distance/center_speed → 47피처"""
    df = df.copy()
    dt = _compute_dt(df['timestamp'].values)

    for name, a, b, c in _JOINT_TRIPLETS:
        angle = _calc_angle(a, b, c, df)
        omega = _central_diff(angle, dt, to_radian=True)
        alpha = _central_diff(omega,  dt, to_radian=False)
        df[f'{name}_angle']                = angle
        df[f'{name}_angular_velocity']     = omega
        df[f'{name}_angular_acceleration'] = alpha

    coords = (
        df[['kp23_x', 'kp23_y', 'kp23_z']].values
        + df[['kp24_x', 'kp24_y', 'kp24_z']].values
    ) / 2
    diff = np.diff(coords, axis=0, prepend=coords[:1])
    df['center_distance'] = np.linalg.norm(diff, axis=1)

    ts        = df['timestamp'].values
    dt_simple = np.diff(ts, prepend=ts[0])
    dt_simple = np.where(dt_simple == 0, 1e-6, dt_simple)
    df['center_speed'] = df['center_distance'] / dt_simple
    return df


# ── 전처리 Step6 ───────────────────────────────────────────────────────────────

def _step6_scale(df: pd.DataFrame) -> np.ndarray:
    """47개 고정 FEATURE_COLUMNS 순서로 StandardScaler.transform"""
    X = df[FEATURE_COLUMNS].copy()
    X = X.replace([np.inf, -np.inf], 0.0).fillna(0.0)
    return _scaler.transform(X.values)


# ── 핵심 추론 함수 (동기, 스레드 풀에서 실행) ──────────────────────────────────

def infer_landmarks(landmarks: list, device_id: str, timestamp: float) -> dict:
    """
    landmark JSON → 30프레임 윈도우 → XGBoost → {"score": float, "fall": bool, "features": dict}
    윈도우 미달 / STRIDE 미달 시 → {"score": 0.0, "fall": False, "features": {}}
    """
    if len(landmarks) != 33:
        return {"score": 0.0, "fall": False, "features": {}}

    # ── landmark JSON → row dict (main.py build_row() 대응) ───────────────
    raw: dict = {}
    for i, lm in enumerate(landmarks):
        raw[f"kp{i}_x"]          = lm["x"]
        raw[f"kp{i}_y"]          = lm["y"]
        raw[f"kp{i}_z"]          = lm["z"]
        raw[f"kp{i}_visibility"]  = lm["v"]
    raw["timestamp"] = timestamp  # Android 기기 시간 사용

    # ── 윈도우 버퍼 관리 (Method A — deque 단일 책임) ─────────────────────
    buf = _get_buffer(device_id)
    buf.append(raw)
    _frame_counts[device_id] = _frame_counts.get(device_id, 0) + 1

    if len(buf) < WINDOW_SIZE:
        return {"score": 0.0, "fall": False, "features": {}}
    if _frame_counts[device_id] % STRIDE != 0:
        return {"score": 0.0, "fall": False, "features": {}}

    # ── 전처리 Step2~6 + XGBoost 추론 ─────────────────────────────────────
    try:
        df_win = pd.DataFrame(buf)
        df_win = _step2_resolve_nan(df_win)
        df_win = _step3_smoothing_savgol(df_win)
        df_win = _step4_pose_normalize(df_win)
        df_win = _step5_make_features(df_win)
        X      = _step6_scale(df_win)

        proba = _model.predict_proba(X)          # shape (30, 2)
        score = float(proba[:, 1].mean() * 100)  # 30프레임 평균
        fall  = bool(score >= CRITICAL_THRESHOLD)
        feats = df_win[FEATURE_COLUMNS].iloc[-1].to_dict()
        return {"score": score, "fall": fall, "features": feats}
    except Exception as e:
        print(f"[ai.engine] 추론 오류: {e}")
        return {"score": 0.0, "fall": False, "features": {}}


async def infer_landmarks_async(landmarks: list, device_id: str, timestamp: float) -> dict:
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, infer_landmarks, landmarks, device_id, timestamp)
