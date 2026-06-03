# AI 추론 엔진 마이그레이션 계획
> **기준 레포**: `OnSafe/ai-server/main.py`  
> **대상 파일**: `On-safe-backend/app/ai/engine.py` 및 관련 파일  
> **작성일**: 2026-05-28  
> **최종 완료**: 2026-06-03 (PR #12 Step 1~6 완료 / PR #14 `_classify_level()` 추가)  
> **상태**: ✅ **Step 1~6 전체 완료**

---

## 배경

`On-safe-backend/app/ai/engine.py`는 초기 구현으로 아래 문제를 가지고 있다.

| 문제 영역 | 현재 (engine.py) | 기준 (main.py) |
|---|---|---|
| 입력 방식 | 서버에서 JPEG → MediaPipe 실행 | 폰 on-device MediaPipe → landmark JSON 수신 |
| 모델 | `decision_tree_model.pkl` | `xgb_model.pkl` (XGBoost) |
| 전처리 | NaN 보정 없음, 단일 프레임 | NaN 보정 + 30프레임 윈도우 |
| 각도 계산 | arccos (불안정) | arctan2 (안정) |
| 피처 수 | 동적 선택, center 6개 | 고정 47개, center 2개 |
| 피처 순서 | ankle → torso → spine | torso → spine → ankle |
| 위험 점수 | 단일 프레임 predict_proba | 30프레임 평균 → 안정적 |
| 임계값 | 정상 ≤50 / 주의 51~75 / 위험 ≥76 | 정상 <40 / WARNING 40~69 / CRITICAL ≥70 (**최종 결정: 기존 51/76 유지 — 아래 Step 4 참고**) |

---

## 작업 목록

### Step 1 — pkl 파일 교체 ✅ 완료

**작업 내용**
```
OnSafe/ai-server/pkl/xgb_model.pkl  →  On-safe-backend/pkl/xgb_model.pkl 복사
OnSafe/ai-server/pkl/scaler.pkl     →  On-safe-backend/pkl/scaler.pkl 교체
On-safe-backend/pkl/decision_tree_model.pkl  →  삭제
```

**주의사항**
- `xgb_model.pkl`과 `scaler.pkl`은 47개 피처를 **main.py의 JOINTS_ORDER 순서**로 학습됨
- scaler를 교체하지 않고 모델만 바꾸면 추론 결과가 완전히 잘못됨 (반드시 쌍으로 교체)

---

### Step 2 — `app/ai/engine.py` 전면 재작성 ✅ 완료

> **최종 구현**: Step 5(WebSocket 전환) 완료로 HTTP+JPEG 제약이 해소됨.
> `cv2`, `mediapipe` **완전 제거**. `infer_landmarks()` 인터페이스로 전환 완료.

#### 2-1. 제거 완료

- `fps` 파라미터 — timestamp 기반 중앙차분으로 대체
- `_savgol_smooth()` — 단일 프레임 SGV → window=7 윈도우 전체 SGV로 대체
- `_centralize()`, `_scale_normalize()` — `_step4_pose_normalize()`로 통합
- `_compute_angle()` (arccos) — `_calc_angle()` (arctan2)로 교체
- `_center_dynamics()` — center 6개 → `center_distance`, `center_speed` 2개로 축소
- `_device_state` dict — `_frame_buffers` deque로 대체

#### 2-2. 추가 제거 (Step 5 WebSocket 전환으로 HTTP+JPEG 제약 해소)

- `import cv2`, `import mediapipe as mp` — WebSocket+landmark 전환으로 **완전 제거**
- `_pose` MediaPipe Pose 싱글턴 — **제거**
- `infer_frame(jpeg_bytes, device_id)` — **`infer_landmarks(landmarks, device_id, timestamp)` 로 교체**
- `infer_frame_async()` — **`infer_landmarks_async()` 로 교체**

#### 2-3. 추가 완료

| 함수 | 역할 |
|---|---|
| `_step2_resolve_nan(df)` | visibility < 0.3 NaN 처리 + 3σ 이상치 + 보간 |
| `_step3_smoothing_savgol(df)` | 윈도우 전체 SGV (window=7, poly=2) |
| `_step4_pose_normalize(df)` | 골반 중앙정렬 + 거리정규화 (윈도우 전체) |
| `_step5_make_features(df)` | arctan2 각도 + timestamp 중앙차분 속도/가속도 |
| `_step6_scale(df)` | 47개 고정 FEATURE_COLUMNS로 스케일링 |
| `_frame_buffers` / `_frame_counts` | device_id별 deque 윈도우 버퍼 (Method A) |

#### 2-4. 고정 상수

```python
WINDOW_SIZE        = 30    # 슬라이딩 윈도우 프레임 수
STRIDE             = 5     # 추론 호출 간격
WARNING_THRESHOLD  = 51.0  # 시스템 기준 51/76으로 통일 (최초 계획 40/70에서 변경)
CRITICAL_THRESHOLD = 76.0  # Kotlin InternalService·RiskLevel.kt와 일치

_JOINTS_ORDER = [
    'neck', 'shoulder_balance',
    'shoulder_left', 'shoulder_right',
    'elbow_left', 'elbow_right',
    'hip_left', 'hip_right',
    'knee_left', 'knee_right',
    'torso_left', 'torso_right', 'spine',  # ← ankle보다 앞
    'ankle_left', 'ankle_right',
]
# FEATURE_COLUMNS 총 47개 (서버 시작 시 assert 검증)
```

#### 2-5. 현재 공개 인터페이스 (최종 구현)

```python
# 최초 계획 (fps 파라미터 있었음)
infer_frame(jpeg_bytes: bytes, device_id: str, fps: float) -> dict

# 중간 단계 (fps 제거)
infer_frame(jpeg_bytes: bytes, device_id: str) -> dict

# ✅ 최종 구현 (Step 5 WebSocket 전환 완료)
def infer_landmarks(landmarks: list, device_id: str, timestamp: float) -> dict
# 윈도우 미달/STRIDE 미달: {"score": 0.0, "fall": False, "level": "정상", "features": {}}
# 추론 완료:              {"score": float, "fall": bool, "level": str, "features": dict}

async def infer_landmarks_async(landmarks: list, device_id: str, timestamp: float) -> dict
```

#### 2-6. `_classify_level()` 추가 (PR #14)

```python
def _classify_level(score: float) -> str:
    if score >= CRITICAL_THRESHOLD:   # >= 76.0
        return "위험"
    if score >= WARNING_THRESHOLD:    # >= 51.0
        return "주의"
    return "정상"
```

`infer_landmarks()` 반환값에 `level` 필드 포함. `service.py`의 중복 `_score_level()` 제거.

---

### Step 3 — `app/ai/buffer.py` 수정 ✅ 완료

프레임 카운터/추론 판단을 engine.py deque로 이전 (Method A).
Redis는 공유 상태 저장 목적으로만 유지.

**제거 완료**
- `push_frame_count()` — engine.py `_frame_counts` dict로 대체
- `should_infer()` — engine.py `len(buf) < WINDOW_SIZE` 체크로 대체
- Redis 키 `frame_count:{device_id}` 미사용

**유지 항목**
- `save_score()`, `get_score()` — score:{user_id} TTL 30s
- `save_latest_frame()`, `get_latest_frame()` — frame:{device_id} TTL 5s
- `check_caution_cooldown()` — caution_cd:{user_id} TTL 300s

**service.py 연동 변경 (Step 3 스코프)**
- import에서 `push_frame_count`, `should_infer` 제거
- `process_stream()` 내 두 호출 제거
- 윈도우 미달 판단: `should_infer()` → `result["features"]` 빈 딕셔너리 체크로 전환

---

### Step 4 — `app/domain/camera/service.py` 수정 ✅ 완료

> **최종 결정**: 임계값·레벨 문자열은 기존(51/76, 한국어) 유지. Kotlin 결합도 이유로 변경 범위 축소.

#### 4-1. `process_stream()` 시그니처 — 유지 (Step 5에서 변경 예정)

```python
# Step 5 전까지 HTTP+JPEG 인터페이스 유지
async def process_stream(jpeg_bytes: bytes, user_id: str, device_id: str) -> StreamResponse
```

#### 4-2. 실제 변경 완료 내용

`_save_realtime_data()` allowed 피처 목록 — center 6개 → 2개:

```python
# 변경 전 — engine.py Step2 이후 존재하지 않는 피처 포함
'center_speed', 'center_acceleration', 'center_displacement',
'center_velocity_change', 'center_mean_speed', 'center_mean_acceleration'

# 변경 후 — engine.py FEATURE_COLUMNS와 일치
'center_distance', 'center_speed'
```

#### 4-3. 이후 변경 이력

| 항목 | 당시 결정 | 최종 결과 |
|---|---|---|
| `_score_level()` | Step 4에서 76/51 유지 결정 | **PR #14에서 `engine.py` `_classify_level()`로 통합, `service.py`에서 제거됨** |
| 레벨 문자열 | "위험"/"주의"/"정상" 유지 | 동일하게 유지 (현재도 동일) |
| 분기 로직 | 숫자 비교 유지 | `service.py` 하드코딩 유지 (76, 51), Kotlin은 PR #15에서 `RiskLevel` 상수로 교체 |

---

### Step 5 — WebSocket 프로토콜 전환 ✅ 완료

> **영향 파일**: router.py / main.py / engine.py / service.py / buffer.py (전체 완료)  
> **인증**: `?token=` 쿼리파라미터 (Kotlin WS 패턴과 일치)  
> **경로**: `/ws/stream` (Python :8000, 사용자 모드 전용)

#### 프로토콜 변경

```
# 기존 (HTTP+JPEG)
POST /api/camera/stream  multipart(frame: JPEG, user_id, device_id)

# 변경 후 (WebSocket+landmark)
WS /ws/stream?token={jwt}
  Android → {"type": "init",  "user_id": "...", "device_id": "..."}
  Android → {"type": "frame", "frame": N, "timestamp": T,
              "landmarks": [{"x":..,"y":..,"z":..,"v":..}, ...]}  # 33개
  서버     → {"type": "result", "fall_score": N, "fall": bool, "level": "..."}
```

#### 5-1. `router.py` + `main.py` ✅ 완료

- `POST /api/camera/stream` 제거
- `WS /ws/stream` 추가 (`ws_router` 분리, `main.py`에 등록)
- JWT 검증: `decode_token(token)` → 실패 시 `close(code=1008)`
- init / frame 메시지 분기 처리, `service.process_frame()` 호출

#### 5-2. `app/ai/engine.py` ✅ 완료

JPEG 입력 제거, landmark JSON 직접 수신으로 전환.

```python
# 변경 전
def infer_frame(jpeg_bytes: bytes, device_id: str) -> dict:
    arr   = np.frombuffer(jpeg_bytes, np.uint8)   # JPEG 디코딩
    frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)   # cv2 변환
    res   = _pose.process(rgb)                    # MediaPipe 실행
    raw   = {f"kp{i}_{ax}": getattr(lm, ax) ...} # landmark 추출
    raw["timestamp"] = time.time()                # 서버 시간

# 변경 후
def infer_landmarks(landmarks: list, device_id: str, timestamp: float) -> dict:
    raw = {
        f"kp{i}_x": lm["x"], f"kp{i}_y": lm["y"],
        f"kp{i}_z": lm["z"], f"kp{i}_visibility": lm["v"]
        for i, lm in enumerate(landmarks)
    }
    raw["timestamp"] = timestamp  # Android 기기 시간 사용
    # Step2~6 로직 동일
```

제거 대상: `import cv2`, `import mediapipe as mp`, `_pose` 싱글턴, `_load_models()`의 MediaPipe 초기화  
추가: `infer_landmarks()`, `infer_landmarks_async()`

#### 5-3. `app/domain/camera/service.py` ✅ 완료

`process_stream()` 제거, `process_frame()` 신규 구현 완료.

```python
# 추가할 함수
async def process_frame(landmarks: list, timestamp: float, user_id: str, device_id: str) -> StreamResponse:
    result = await infer_landmarks_async(landmarks, device_id, timestamp)
    if not result["features"]:
        return StreamResponse(score=0.0, fall=False)
    score: float = result["score"]
    fall: bool   = result["fall"]
    level = _score_level(score)

    await save_score(user_id, score, level)
    await _save_realtime_data(user_id, result["features"], score)
    await _update_realtime(user_id, score, level)

    log_id: str | None = None
    if score >= 76 or fall:
        log_id = await _save_fall_log(user_id, device_id, score, fall, None)
    elif score >= 51:
        if await check_caution_cooldown(user_id):
            log_id = await _save_fall_log(user_id, device_id, score, fall, None)

    return StreamResponse(score=score, fall=fall, log_id=log_id)
```

> **보호자 릴레이 보류**: `_publish_frame()` 호출 여부는 릴레이 방식 결정 후 반영

#### 5-4. `app/ai/buffer.py` ✅ 완료

`save_latest_frame()` 호출이 `process_stream()` 제거로 자연 해소됨.  
함수 자체(save/get_latest_frame)는 보호자 릴레이 방식 미결정으로 코드 유지 중 (미사용 주석 표시).

---

### Step 6 — requirements.txt / Dockerfile / 코드 정리 ✅ 완료

#### requirements.txt — 제거
```
opencv-python (또는 opencv-python-headless)
mediapipe
```

#### requirements.txt — 유지
```
fastapi
uvicorn
xgboost
joblib
numpy
pandas
scipy
httpx
redis
```

#### 코드 정리 (Step 5 완료 후)

- `service.py`: `process_stream(jpeg_bytes, ...)` 제거 (WebSocket 전환 완료 후 불필요)
- `buffer.py`: `save_latest_frame()` / `get_latest_frame()` — 보호자 릴레이 결정에 따라 제거 또는 대체
- `engine.py`: `infer_frame()` / `infer_frame_async()` 제거 (infer_landmarks로 대체 완료 후)

---

## 비용 분석

> **전제 조건**
> - 사용 패턴: 1일 4시간 모니터링 (노인 돌봄 서비스 기준)
> - 서버 리전: ap-northeast-2 (서울)
> - JPEG 프레임 크기: 640×480, quality 70 ≈ 20KB/frame
> - landmark JSON 크기: 33개 좌표 + 메타 ≈ 2KB/frame
> - 낙상 이벤트: 월 5회/사용자 (오탐 포함)
> - MP4 클립: 낙상 전후 10초 ≈ 5MB

### AWS 단가 참고 (ap-northeast-2, 2026년 기준)

| 항목 | 단가 |
|---|---|
| EC2 t3.medium (2vCPU, 4GB) | $0.052/h = $37/월 |
| EC2 t3.large (2vCPU, 8GB) | $0.104/h = $75/월 |
| 데이터 inbound | 무료 |
| 데이터 outbound | $0.09/GB |
| Firebase Storage 저장 | $0.026/GB/월 |
| Firebase Storage 다운로드 | $0.12/GB |

---

### 아키텍처 옵션 정의

비용 분석에서 사용하는 Option A / B는 **보호자 릴레이 방식**에 따른 구분이다.  
AI 추론 로직(engine.py Step 1~6)은 두 옵션 모두 동일하게 적용된다.

| 옵션 | AI 추론 입력 | 보호자 릴레이 | 특징 |
|---|---|---|---|
| **현재** | Android → JPEG 30fps → 서버 MediaPipe | JPEG 30fps 그대로 릴레이 | 비용 최대 |
| **Option A** | Android → landmark 30fps (AI) + JPEG 5fps (릴레이) | JPEG 5fps 릴레이 | 이중 스트림 |
| **Option B** | Android → landmark 30fps (AI만) | JPEG 별도 저주파 (2~3fps) | 단일 스트림 + 저주파 릴레이 |
| **Option C** | Android 자체 추론 (ONNX) | 별도 선택 | `ai-ondevice-plan.md` 참고 |

> **현재 진행 중인 engine.py 마이그레이션(Step 1~6)은 Option A/B 공통 작업이다.**  
> 보호자 릴레이 방식(A vs B)은 Step 5(router.py) 작업 시 결정한다.

---

### 데이터 타입별 시간당 트래픽 (1인 기준)

```
JPEG 30fps:  30fps × 20KB × 3,600s  = 2,160 MB/h  (현재)
JPEG  5fps:   5fps × 20KB × 3,600s  =   360 MB/h
JPEG  2fps:   2fps × 20KB × 3,600s  =   144 MB/h
JPEG  3fps:   3fps × 20KB × 3,600s  =   216 MB/h
landmark 30fps: 30fps × 2KB × 3,600s =  216 MB/h
```

---

### 소규모 시나리오 — 사용자 50명

| 항목 | 현재 (JPEG 30fps) | Option A (landmark+JPEG 5fps) | **Option B + 서버릴레이 2fps** | **Option B + 서버릴레이 3fps** |
|---|---|---|---|---|
| 서버 인스턴스 | t3.large × 2대 $150 | t3.medium $37 | t3.medium $37 | t3.medium $37 |
| AI 추론 inbound | 50 × 4h × 30d × 2,160MB = 12,960GB 무료 | landmark: 50 × 4h × 30d × 216MB = 1,296GB 무료 | landmark: 1,296GB 무료 | landmark: 1,296GB 무료 |
| 릴레이 inbound | — | JPEG 5fps: 50 × 4h × 30d × 360MB = 2,160GB 무료 | JPEG 2fps: 50 × 4h × 30d × 144MB = 864GB 무료 | JPEG 3fps: 50 × 4h × 30d × 216MB = 1,296GB 무료 |
| 릴레이 outbound | 12,960GB × $0.09 = **$1,166** | 2,160GB × $0.09 = **$194** | 864GB × $0.09 = **$78** | 1,296GB × $0.09 = **$117** |
| Storage (MP4) | — | 50명 × 5건 × 5MB × $0.026 = $0.03 저장 + $0.15 다운로드 ≈ **$1** | ≈ **$1** | ≈ **$1** |
| **월 합계** | **≈ $1,316** | **≈ $232** | **≈ $116** | **≈ $155** |

---

### 중규모 시나리오 — 사용자 500명

| 항목 | 현재 (JPEG 30fps) | Option A (landmark+JPEG 5fps) | **Option B + 서버릴레이 2fps** | **Option B + 서버릴레이 3fps** |
|---|---|---|---|---|
| 서버 인스턴스 | t3.large × 3대 $225 | t3.medium $37 | t3.medium $37 | t3.medium $37 |
| 릴레이 outbound | 129,600GB × $0.09 = **$11,664** | 21,600GB × $0.09 = **$1,944** | 8,640GB × $0.09 = **$778** | 12,960GB × $0.09 = **$1,166** |
| Storage (MP4) | — | ≈ **$110** | ≈ **$110** | ≈ **$110** |
| **월 합계** | **≈ $11,889** | **≈ $2,091** | **≈ $925** | **≈ $1,313** |

---

### 비용 구조 핵심 인사이트

```
비용의 90% 이상은 보호자 앱 릴레이 outbound가 지배한다.
저장(MP4/썸네일)은 전체 비용에서 1% 미만.

현재 대비 절감률 (500명 기준):
  Option A  (landmark + JPEG 5fps)  :  현재 대비 82% 절감  → $2,091/월
  Option B  (landmark + JPEG 2fps)  :  현재 대비 92% 절감  → $  925/월
  Option B  (landmark + JPEG 3fps)  :  현재 대비 89% 절감  → $1,313/월
```

---

### 보호자 릴레이 방식별 추가 비교

| 방식 | 월 비용 (500명) | 영상 품질 | 구현 난이도 | 비고 |
|---|---|---|---|---|
| 서버 릴레이 2fps | $925 | 매우 낮음 | 낮음 | 슬라이드쇼 수준 |
| 서버 릴레이 3fps | $1,313 | 낮음 | 낮음 | **단기 권장** |
| 서버 릴레이 5fps | $1,981 | 보통 | 낮음 | Option A와 유사 |
| WebRTC P2P | $8~$400 | 30fps 고화질 | 높음 | **중장기 권장** |
| Agora SD | $3,564 | 중간 | 낮음 | 사용자 증가 시 비효율 |

### 권장 로드맵

```
단기 (마이그레이션 직후):
  Option B + 서버 릴레이 3fps
  → 구현 변경 최소, 현재 대비 89% 비용 절감
  → UX는 다소 저하되나 낙상 감지 목적에는 충분

중장기 (서비스 안정화 후):
  WebRTC P2P 전환
  → 30fps 고화질 유지 + 비용 대폭 절감
  → TURN 서버 설계 별도 필요 (P2P 불가 단말 대응)
```

---

## Option C — On-device 추론 (Android ONNX Runtime)

> 서버 AI 추론을 제거하고 Android 기기에서 직접 추론하는 대안 아키텍처.  
> 현재 서버 추론 방식(Option A/B)과 비교해 장단점이 명확히 갈린다.

### 아키텍처 구조

```
현재 / Option A·B (서버 추론)        Option C (On-device 추론)
─────────────────────────────        ──────────────────────────────
Android                              Android
  └─ JPEG or landmark → AI 서버        ├─ MediaPipe Android SDK
                                       │    → 관절 좌표 추출
AI 서버 (FastAPI)                      ├─ 앱 메모리 (3프레임 버퍼)
  ├─ 전처리 Step 2~6                   ├─ Kotlin 파생변수 계산
  ├─ XGBoost 추론                      │    (각속도, 각가속도 등)
  └─ Spring Boot 이벤트 발행           ├─ ONNX Runtime 추론
                                       └─ score ≥ 70 시 Spring Boot 직접 HTTP
AI 서버 필요 ($37~$75/월)             AI 서버 불필요 ($0/월)
```

---

### Step별 호환성 분석 (3프레임 버퍼 기준)

| Step | 내용 | 3프레임 호환성 | On-device 대안 |
|---|---|---|---|
| Step 2 | NaN 보정 | ⚠️ 부분 | visibility < 0.3만 NaN 처리 (3σ 이상치 검출 생략) |
| Step 3 | SGV 스무딩 (window=7) | ❌ 불가 | window=3 SGV 또는 EMA 적용 |
| Step 4 | 골반 정규화 | ✅ 완전 | 동일 수식 Kotlin 구현 |
| Step 5-a | 각도 (arctan2) | ✅ 완전 | 동일 수식 Kotlin 구현 |
| Step 5-b | 각속도 (중앙차분) | ✅ 가능 | 3프레임 [t-2, t-1, t]에서 t-1의 각속도 계산 |
| Step 5-c | 각가속도 | ❌ 항상 0 | **각속도 링 버퍼 별도 유지 필요** (아래 참고) |
| Step 5-d | center 피처 | ✅ 완전 | 2프레임으로 계산 가능 |
| Step 6 | StandardScaler | ✅ 변환 가능 | ONNX 파이프라인 또는 파라미터 Kotlin 직접 적용 |

#### 각가속도 문제 상세

3프레임 버퍼에서 중앙차분으로 각속도를 구하면 결과가 `[0, ω₁, 0]`이 된다.  
이 값으로 다시 각가속도를 구하면 `(0 - 0) / 2Δt = 0`으로 항상 0에 수렴한다.

```
해결: 각속도 링 버퍼를 별도로 유지
  velocityRingBuffer: deque(maxlen=3)   // 프레임별 각속도 저장
  새 프레임 도착 → 각속도 계산 → 버퍼에 추가
  각가속도 = (ω[+1] - ω[-1]) / 2Δt    // 버퍼의 중앙차분
  → 실질적으로 5프레임 분량의 정보 사용
```

---

### 정확도 리스크

```
높음: 각가속도 47개 피처 중 15개(32%)가 상시 0
      → 모델 학습 분포와 크게 달라 오탐·미탐 가능성
         (각속도 링 버퍼 도입으로 해결 가능)

중간: SGV window=7 → window=3으로 축소
      → 노이즈가 각도·각속도 피처에 더 많이 반영
      → 빠른 동작에서 오탐 가능성

낮음: NaN 보정 단순화
      → MediaPipe Android SDK 자체 신뢰도가 높아 영향 제한적
```

---

### 모델 변환 (XGBoost → ONNX)

```python
# 변환 스크립트 (Python, 1회 실행)
# 위치: On-safe-backend/scripts/convert_to_onnx.py (신규 생성)

import joblib, json
from onnxmltools import convert_xgboost
from onnxmltools.utils import save_model
from skl2onnx.common.data_types import FloatTensorType

model  = joblib.load("pkl/xgb_model.pkl")
scaler = joblib.load("pkl/scaler.pkl")

# XGBoost → ONNX (입력: 1행 × 47피처)
onnx_model = convert_xgboost(
    model,
    initial_types=[('input', FloatTensorType([None, 47]))]
)
save_model(onnx_model, "xgb_model.onnx")   # Android assets/에 배치

# StandardScaler 파라미터 추출 → Kotlin에서 직접 적용
json.dump({
    "mean":          scaler.mean_.tolist(),
    "scale":         scaler.scale_.tolist(),
    "feature_names": scaler.feature_names_in_.tolist()
}, open("scaler_params.json", "w"))
```

Android 프로젝트에 추가할 의존성:
```kotlin
// android/app/build.gradle.kts
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
```

---

### Option C 선택 시 서버 측 변경사항

FastAPI AI 서버 전체 제거 가능. Spring Boot만 유지.

| 파일 | 변경 |
|---|---|
| `app/ai/engine.py` | **삭제** |
| `app/ai/buffer.py` | **삭제** |
| `app/domain/camera/service.py` | **삭제** |
| `app/domain/camera/router.py` | **삭제** |
| `app/main.py` | **삭제** (FastAPI 앱 전체) |
| Spring Boot `InternalController.kt` | fall-event 수신 엔드포인트만 유지 |

---

### Option C 비용 (500명 기준)

| 항목 | 비용 |
|---|---|
| AI 서버 (FastAPI) | **$0** (불필요) |
| 보호자 릴레이 서버 | 방식에 따라 $8~$1,313 (비용 분석 섹션 참고) |
| Android 배터리 소모 | 추가 비용 없음 (사용자 기기 부담) |
| **월 합계** | **≈ $8~$1,313** (릴레이 방식에 따라) |

---

### Option C 경로 선택 가이드

```
경로 A (단기, 현재 모델 그대로 사용):
  1. 각속도 링 버퍼 도입으로 각가속도 문제 해결
  2. XGBoost → ONNX 변환
  3. StandardScaler 파라미터 Kotlin 적용
  4. SGV window=3 적용 (학습과 불일치 허용)
  → 재학습 없이 빠르게 적용 가능
  → 정확도 저하 가능성 있음 (실측 필요)

경로 B (중기, 재학습):
  1. DataScience 노트북에서 3프레임 기준 피처로 파이프라인 수정
  2. 모델 재학습
  3. ONNX 변환 후 배포
  → 학습·추론 파이프라인 완전 일치
  → 가장 높은 정확도 보장
```

---

### 아키텍처 옵션 최종 비교

| | Option A | Option B + 3fps 릴레이 | **Option C** |
|---|---|---|---|
| AI 추론 위치 | 서버 | 서버 | **Android 기기** |
| 서버 AI 비용 | $37/월 | $37/월 | **$0** |
| 릴레이 비용 (500명) | $1,944 | $1,313 | 별도 선택 |
| 레이턴시 | 네트워크 왕복 | 네트워크 왕복 | **거의 없음** |
| 오프라인 동작 | ❌ | ❌ | **✅** |
| 배터리 소모 | 없음 | 없음 | **추가 있음** |
| 모델 배포 | 서버 재시작 | 서버 재시작 | **앱 업데이트 필요** |
| 재학습 필요 | 불필요 | 불필요 | **권장** |
| 구현 난이도 | 중 | 중 | **높음** |

---

## 검증 체크리스트

### Option A / B 공통

작업 완료 후 아래 항목을 순서대로 확인한다.

- [ ] `assert len(FEATURE_COLUMNS) == 47` 서버 시작 시 통과
- [x] `GET /health` → `{"status": "ok", "model_loaded": true, "scaler_loaded": true}` ✅ (PR #12 추가)
- [x] `scripts/test_ws_stream.py`로 WebSocket 연결 및 추론 결과 수신 확인 ✅ (ai-migration-test-report 참조)
- [x] WARNING 이벤트: score **51~75** → Spring Boot `/internal/fall-log` 호출 확인 ✅
- [x] CRITICAL 이벤트: score **≥76** → 쿨다운 **300초(5분)** 내 중복 WARNING 발행 차단 확인 ✅
- [x] 30프레임 미만 수신 시 추론 건너뜀 확인 ✅

### Option C 추가 체크리스트

- [ ] `convert_to_onnx.py` 실행 후 `xgb_model.onnx` 생성 확인
- [ ] ONNX 모델 입력 shape `(1, 47)` 검증
- [ ] Android ONNX Runtime에서 단일 추론 latency 측정 (목표: < 10ms)
- [ ] 각속도 링 버퍼 도입 후 각가속도 값이 0이 아닌 것 확인
- [ ] 실제 낙상 동작 테스트에서 score ≥ 70 도달 확인

---

## 참고 파일

| 파일 | 역할 |
|---|---|
| `OnSafe/ai-server/main.py` | **소스 오브 트루스** — 모든 로직의 기준 |
| `OnSafe/ai-server/pkl/xgb_model.pkl` | 교체 대상 XGBoost 모델 |
| `OnSafe/ai-server/pkl/scaler.pkl` | 교체 대상 스케일러 |
| `OnSafe/ai-server/ws_test_client.py` | WebSocket 연결 테스트 클라이언트 |
| `On-safe-backend/app/ai/engine.py` | 수정 대상 |
| `On-safe-backend/app/ai/buffer.py` | 부분 수정 대상 |
| `On-safe-backend/app/domain/camera/service.py` | 수정 대상 |
| `On-safe-backend/app/domain/camera/router.py` | 수정 대상 |
