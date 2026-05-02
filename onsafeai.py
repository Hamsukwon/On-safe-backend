from flask import Flask, Response, request, jsonify, url_for
import cv2
import mediapipe as mp
import numpy as np
import threading
import time
from datetime import datetime
import pandas as pd
import joblib
from scipy.signal import savgol_filter
import os
import socket
from collections import deque
from flask_cors import CORS  # 앱 통신을 위한 CORS 허용 추가

# DB 설정 가져오기
from db_config import get_db_connection, engine


app = Flask(__name__)
app.secret_key = os.urandom(24)
CORS(app)  # 모든 경로에 대해 외부 앱(안드로이드)의 접속을 허용


# AI 모델 로드
scaler = joblib.load("pkl/scaler.pkl")
model = joblib.load("pkl/decision_tree_model.pkl")

# 설정값
WINDOW_SIZE = 5
POLY_ORDER = 2
RECORD_DIR = "static/recordings" # 주의 및 경고 영상 저장 경로
if not os.path.exists(RECORD_DIR):
    os.makedirs(RECORD_DIR)

# 전역 변수
kp_buffer = {f'kp{i}_{axis}': deque(maxlen=WINDOW_SIZE) for i in range(33) for axis in ['x', 'y', 'z']}
mp_pose = mp.solutions.pose
pose = mp_pose.Pose(min_detection_confidence=0.5, min_tracking_confidence=0.5)

latest_frame = None
frame_lock = threading.Lock()
current_user_id = None
cap = None
fps = 30
is_recording = False

prev_angles = {}    # 이전 각도
prev_angular_velocity = {}  # 이전 각속도
prev_center = None  # 이전 중심 위치
prev_center_speed = 0.0 # 이전 중심 속도

latest_score = 0.0

# 사용하는 관절 포이트
joint_triplets = [
    ('neck', 0, 11, 12), ('shoulder_balance', 11, 0, 12),
    ('shoulder_left', 23, 11, 13), ('shoulder_right', 24, 12, 14),
    ('elbow_left', 11, 13, 15), ('elbow_right', 12, 14, 16),
    ('hip_left', 11, 23, 25), ('hip_right', 12, 24, 26),
    ('knee_left', 23, 25, 27), ('knee_right', 24, 26, 28),
    ('ankle_left', 25, 27, 31), ('ankle_right', 26, 28, 32),
    ('torso_left', 0, 11, 23), ('torso_right', 0, 12, 24),
    ('spine', 0, 23, 24),
]


# --- AI 연산 함수들 (기존 로직 유지) ---
# 사비츠키-골레이 필터 적용 함수
def smooth_with_savgol(row_dict):
    smoothed_row = row_dict.copy()
    for key in kp_buffer.keys():
        if key in row_dict:
            kp_buffer[key].append(row_dict[key])
            if len(kp_buffer[key]) == WINDOW_SIZE:
                # pkl 없이 scipy 함수로 직접 필터링
                data = np.array(kp_buffer[key])
                filtered_signal = savgol_filter(data, WINDOW_SIZE, POLY_ORDER)
                smoothed_row[key] = filtered_signal[-1]
    return smoothed_row


def compute_center_dynamics(df, fps=30, left_pelvis='kp23', right_pelvis='kp24'):
    global prev_center, prev_center_speed
    centers = []
    for _, row in df.iterrows():
        try:
            center = np.array([
                (row[f'{left_pelvis}_x'] + row[f'{right_pelvis}_x']) / 2,
                (row[f'{left_pelvis}_y'] + row[f'{right_pelvis}_y']) / 2,
                (row[f'{left_pelvis}_z'] + row[f'{right_pelvis}_z']) / 2
            ])
        except KeyError:
            center = np.array([np.nan, np.nan, np.nan])

        displacement = speed = acceleration = velocity_change = 0.0
        if prev_center is not None:
            displacement = np.linalg.norm(center - prev_center)
            speed = displacement * fps
            acceleration = (speed - prev_center_speed) * fps
            velocity_change = abs(speed - prev_center_speed)

        centers.append({
            'center_displacement': displacement,
            'center_speed': speed,
            'center_acceleration': acceleration,
            'center_velocity_change': velocity_change,
            'center_mean_speed': speed,
            'center_mean_acceleration': acceleration
        })
        prev_center, prev_center_speed = center, speed
    return pd.DataFrame(centers)


def centralize_kp(df, pelvis_idx=(23, 24)):
    df_central = df.copy()
    pelvis_x = (df[f'kp{pelvis_idx[0]}_x'] + df[f'kp{pelvis_idx[1]}_x']) / 2
    pelvis_y = (df[f'kp{pelvis_idx[0]}_y'] + df[f'kp{pelvis_idx[1]}_y']) / 2
    pelvis_z = (df[f'kp{pelvis_idx[0]}_z'] + df[f'kp{pelvis_idx[1]}_z']) / 2
    for x, y, z in zip([c for c in df.columns if '_x' in c], [c for c in df.columns if '_y' in c],
                       [c for c in df.columns if '_z' in c]):
        df_central[x] -= pelvis_x;
        df_central[y] -= pelvis_y;
        df_central[z] -= pelvis_z
    return df_central


def scale_normalize_kp(df, ref_joints=(23, 24)):
    df_scaled = df.copy()
    l, r = ref_joints
    scale = np.sqrt((df[f'kp{l}_x'] - df[f'kp{r}_x']) ** 2 + (df[f'kp{l}_y'] - df[f'kp{r}_y']) ** 2 + (
                df[f'kp{l}_z'] - df[f'kp{r}_z']) ** 2)
    scale[scale == 0] = 1
    for col in df.columns:
        if any(s in col for s in ['_x', '_y', '_z']): df_scaled[col] = df[col] / scale
    return df_scaled


def compute_angle(a, b, c):
    ba, bc = a - b, c - b
    cosine_angle = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-8)
    return np.degrees(np.arccos(np.clip(cosine_angle, -1.0, 1.0)))


def calculate_angles(row, fps=30):
    global prev_angles, prev_angular_velocity
    result = {}
    for j_name, a_idx, b_idx, c_idx in joint_triplets:
        try:
            a, b, c = [np.array([row[f'kp{i}_x'], row[f'kp{i}_y'], row[f'kp{i}_z']]) for i in [a_idx, b_idx, c_idx]]
            angle = compute_angle(a, b, c)
            result[f'{j_name}_angle'] = angle
            p_ang = prev_angles.get(f'{j_name}_angle', angle)
            ang_vel = (angle - p_ang) * fps
            result[f'{j_name}_angular_velocity'] = ang_vel
            p_vel = prev_angular_velocity.get(f'{j_name}_angular_velocity', ang_vel)
            result[f'{j_name}_angular_acceleration'] = (ang_vel - p_vel) * fps
            prev_angles[f'{j_name}_angle'], prev_angular_velocity[f'{j_name}_angular_velocity'] = angle, ang_vel
        except KeyError:
            result[f'{j_name}_angle'] = result[f'{j_name}_angular_velocity'] = result[
                f'{j_name}_angular_acceleration'] = 0.0
    return result


# --- 카메라 및 분석 루프 ---
def auto_discover_camera(base_url):
    """기존 URL의 IP 대역을 스캔하여 현재 살아있는 카메라 IP를 찾음"""
    if not base_url or "http" not in base_url:
        return base_url

def get_camera_url(user_id):
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT camera_url FROM users WHERE user_id = %s", (user_id,))
                row = cursor.fetchone()
                return row['camera_url'] if row else None
    except Exception as e:
        print(f"⚠️ URL 조회 오류: {e}");
        return None


def get_video_capture(url):
    try:
        return cv2.VideoCapture(url if url != '0' else 0)
    except:
        return None


def connect_camera_loop():
    global cap, fps, current_user_id
    while True:
        if cap is not None and cap.isOpened():
            time.sleep(1)
            continue

        if current_user_id:
            db_url = get_camera_url(current_user_id)
            if db_url:
                # 1. 먼저 DB에 저장된 주소로 시도
                temp_cap = cv2.VideoCapture(db_url)
                if not temp_cap.isOpened():
                    # 2. 실패 시 주변 IP 자동 탐색 (마지막 자리 변동 대응)
                    new_url = auto_discover_camera(db_url)
                    temp_cap = cv2.VideoCapture(new_url)

                if temp_cap.isOpened():
                    cap = temp_cap
                    fps_v = int(cap.get(cv2.CAP_PROP_FPS))
                    fps = fps_v if fps_v > 0 else 30
                    print(f"🚀 카메라 연결 성공!")
                else:
                    print("📴 카메라를 찾을 수 없습니다. 5초 후 재시도...")
                    time.sleep(5)
        else:
            time.sleep(2)


def capture_frames():
    global latest_frame, cap, frame_idx, fps, latest_score, latest_label, current_user_id
    last_analysis_time = 0
    # 0.5초마다 분석하도록 간격 설정
    ANALYSIS_INTERVAL = 0.5

    while True:
        if cap is None or not cap.isOpened():
            time.sleep(0.2)
            continue

        ret, frame = cap.read()
        if not ret:
            continue

        # 1. UI 스트리밍용 최신 프레임 업데이트 (원본 유지)
        with frame_lock:
            latest_frame = frame.copy()
            frame_idx += 1

        cur_t = time.time()

        # 정확히 0.5초가 지났을 때만 MediaPipe 분석 수행
        if cur_t - last_analysis_time >= ANALYSIS_INTERVAL:
            last_analysis_time = cur_t

            # [파일 저장 없이 메모리 직접 분석]
            # MediaPipe 처리를 위해 BGR을 RGB로만 변환
            rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            res = pose.process(rgb_frame)

            if res.pose_landmarks:
                # 데이터 추출 로직
                raw_row = {'frame': frame_idx}
                for i, lm in enumerate(res.pose_landmarks.landmark):
                    raw_row[f'kp{i}_x'], raw_row[f'kp{i}_y'], raw_row[f'kp{i}_z'] = lm.x, lm.y, lm.z

                # [Step 1] 사비골 필터로 노이즈 제거
                smoothed_row = smooth_with_savgol(raw_row)
                df = pd.DataFrame([smoothed_row])

                # [Step 2] 데이터 가공 (중심점 역학, 정규화, 각도 계산)
                # fps 인자를 1로 전달하거나 실제 fps를 써서 1초당 변화량을 계산합니다.
                c_info = compute_center_dynamics(df, fps=fps).iloc[-1].to_dict()
                df_processed = scale_normalize_kp(centralize_kp(df))
                calc = calculate_angles(df_processed.iloc[0].to_dict(), fps=fps)
                calc.update(c_info)

                # [Step 3] AI 모델 예측
                if model and scaler:
                    try:
                        feat = [col for col in calc.keys() if any(x in col.lower() for x in ["angle", "center"])]
                        X = pd.DataFrame([calc])[feat].reindex(columns=scaler.feature_names_in_, fill_value=0.0)
                        X_scaled = scaler.transform(X)

                        prob = model.predict_proba(X_scaled)
                        latest_score = float(prob[0][1] * 100)
                        latest_label = "Fall" if model.predict(X_scaled)[0] == 1 else "Normal"

                        calc["risk_score"] = latest_score
                    except Exception as e:
                        print(f"⚠️ AI 예측 오류: {e}")

                # [Step 4] DB 저장
                if current_user_id:
                    save_realtime_data(current_user_id, calc)

        # 시스템 부하 방지를 위한 미세 대기
        time.sleep(0.01)


def gen_frames():
    while True:
        with frame_lock:
            if latest_frame is not None:
                # 640x480으로 크기 조절 (브라우저 부하 감소 핵심)
                f = cv2.resize(latest_frame, (640, 480))
            else:
                f = np.zeros((480, 640, 3), np.uint8)

        # JPEG 품질을 70으로 설정하여 데이터량 축소
        _, buf = cv2.imencode('.jpg', f, [cv2.IMWRITE_JPEG_QUALITY, 70])
        yield (b'--frame\r\nContent-Type: image/jpeg\r\n\r\n' + buf.tobytes() + b'\r\n')
        time.sleep(0.03)  # 초당 약 30프


# DB 저장
def save_realtime_data(user_id, analysis_result):
    if not user_id: return
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                analysis_result['user_id'] = user_id
                # DB 스키마(realtime_screen)에 실제 존재하는 컬럼만 필터링
                # Label 등 스키마에 없는 키는 제외해야 에러가 안 납니다.
                allowed_cols = [
                    'user_id', 'neck_angle', 'neck_angular_velocity', 'neck_angular_acceleration',
                    'shoulder_balance_angle', 'shoulder_balance_angular_velocity',
                    'shoulder_balance_angular_acceleration',
                    'shoulder_left_angle', 'shoulder_left_angular_velocity', 'shoulder_left_angular_acceleration',
                    'shoulder_right_angle', 'shoulder_right_angular_velocity', 'shoulder_right_acceleration',
                    'elbow_left_angle', 'elbow_left_angular_velocity', 'elbow_left_angular_acceleration',
                    'elbow_right_angle', 'elbow_right_angular_velocity', 'elbow_right_angular_acceleration',
                    'hip_left_angle', 'hip_left_angular_velocity', 'hip_left_angular_acceleration',
                    'hip_right_angle', 'hip_right_angular_velocity', 'hip_right_angular_acceleration',
                    'knee_left_angle', 'knee_left_angular_velocity', 'knee_left_angular_acceleration',
                    'knee_right_angle', 'knee_right_angular_velocity', 'knee_right_angular_acceleration',
                    'torso_left_angle', 'torso_left_angular_velocity', 'torso_left_angular_acceleration',
                    'torso_right_angle', 'torso_right_angular_velocity', 'torso_right_angular_acceleration',
                    'spine_angle', 'spine_angular_velocity', 'spine_angular_acceleration',
                    'ankle_left_angle', 'ankle_left_angular_velocity', 'ankle_left_angular_acceleration',
                    'ankle_right_angle', 'ankle_right_angular_velocity', 'ankle_right_angular_acceleration',
                    'center_speed', 'center_acceleration', 'center_displacement',
                    'center_velocity_change', 'center_mean_speed', 'center_mean_acceleration', 'risk_score'
                ]

                final_data = {k: v for k, v in analysis_result.items() if k in allowed_cols}

                columns = ', '.join(final_data.keys())
                placeholders = ', '.join(['%s'] * len(final_data))
                sql = f"INSERT INTO realtime_screen ({columns}) VALUES ({placeholders})"
                cursor.execute(sql, list(final_data.values()))

                # 최신 2000개 유지 (DB 용량 관리)
                cursor.execute(
                    "DELETE FROM realtime_screen WHERE user_id = %s AND id NOT IN "
                    "(SELECT id FROM (SELECT id FROM realtime_screen WHERE user_id = %s ORDER BY timestamp DESC LIMIT 2000) AS t)",
                    (user_id, user_id)
                )
            conn.commit()

            # 80점 이상 시 녹화 실행
            score = final_data.get('risk_score', 0)
            if score >= 80 and not is_recording:
                threading.Thread(target=record_and_save_log, args=(user_id, score), daemon=True).start()
    except Exception as e:
        print(f"❌ DB 저장 오류: {e}")


# 회원가입 실제 DB 저장을 담당할 보조 함수
def background_register(data):
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                sql = """INSERT INTO users 
                         (user_id, password, guardian_name, guardian_phone, ward_name, email, camera_url) 
                         VALUES (%s, %s, %s, %s, %s, %s, %s)"""
                cursor.execute(sql, (
                    data['id'], data['password'], data['username'], data['phone_number'],
                    data['non_guardian_name'], data['mail'], data['camera_url']
                ))
            conn.commit()
            print(f"✅ DB 가입 완료: {data['id']}")
    except Exception as e:
        print(f"❌ 백그라운드 가입 오류: {e}")


# 특정 점수(80) 이상일 때 영상을 녹화하고 detection_logs 테이블에 저장
def record_and_save_log(user_id, score):
    global is_recording, latest_frame
    if is_recording: return

    is_recording = True
    now = datetime.now()
    # 파일명 예시: fall_user1_20231027_143005.mp4
    filename = f"fall_{user_id}_{now.strftime('%Y%m%d_%H%M%S')}.mp4"
    filepath = os.path.join(RECORD_DIR, filename)

    # 영상 설정 (640x480, 20fps 기준 약 10초 녹화 테스트)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    out = cv2.VideoWriter(filepath, fourcc, 20.0, (640, 480))

    print(f"🚨 위험 감지({score}점)! 녹화 시작: {filepath}")

    # 약 10분간(12000 프레임) 녹화
    frames_to_record = 12000
    count = 0
    while count < frames_to_record:
        if latest_frame is not None:
            out.write(latest_frame)
            count += 1
        time.sleep(0.05)

    out.release()
    is_recording = False
    print(f"✅ 녹화 종료 및 DB 기록 중...")

    # [DB 저장] detection_logs 테이블에 입력
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                sql = """INSERT INTO detection_logs (user_id, event_time, risk_score, video_path) 
                         VALUES (%s, %s, %s, %s)"""
                # 웹에서 접근 가능한 경로로 저장 (예: /static/recordings/...)
                web_path = f"/static/recordings/{filename}"
                cursor.execute(sql, (user_id, now, score, web_path))
            conn.commit()
    except Exception as e:
        print(f"❌ detection_logs 저장 오류: {e}")



# --- API 엔드포인트 (앱 전용) ---

@app.route('/api/login', methods=['POST'])
def api_login():
    data = request.json
    u_id, pw = data.get('id'), data.get('password')
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute("SELECT user_id, password FROM users WHERE user_id=%s", (u_id,))
            user = cursor.fetchone()
    if user and user['password'] == pw:
        global current_user_id
        current_user_id = u_id  # 현재 분석 대상을 해당 유저로 설정
        return jsonify({"success": True, "user_id": u_id})
    return jsonify({"success": False, "message": "로그인 실패"})


@app.route('/api/register', methods=['POST'])
def api_register():
    data = request.json
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                sql = "INSERT INTO users (user_id, password, guardian_name, guardian_phone, ward_name, email, camera_url) VALUES (%s, %s, %s, %s, %s, %s, %s)"
                cursor.execute(sql, (
                data['id'], data['password'], data['username'], data['phone_number'], data['non_guardian_name'],
                data['mail'], data['camera_url']))
            conn.commit()
        return jsonify({"success": True})
    except:
        return jsonify({"success": False})


@app.route('/api/history/<user_id>')
def api_history(user_id):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT event_time, risk_score, video_path FROM detection_logs WHERE user_id=%s ORDER BY event_time DESC",
                (user_id,))
            logs = cursor.fetchall()
    return jsonify(logs)


@app.route('/api/score/<user_id>')
def api_score(user_id):
    # 앱에서 실시간 점수만 땡겨갈 때 사용
    return jsonify({"risk_score": latest_score})


@app.route('/video_feed')
def video_feed():
    def gen():
        while True:
            with frame_lock:
                if latest_frame is not None:
                    _, buf = cv2.imencode('.jpg', cv2.resize(latest_frame, (640, 480)))
                    frame = buf.tobytes()
            yield (b'--frame\r\nContent-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
            time.sleep(0.05)

    return Response(gen(), mimetype='multipart/x-mixed-replace; boundary=frame')


if __name__ == "__main__":
    # 카메라 연결 루프는 별도 생략(기존 동일), capture_frames만 기동
    threading.Thread(target=capture_frames, daemon=True).start()
    app.run(host='0.0.0.0', port=5000)