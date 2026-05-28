# AI On-device 추론 계획 (Option C)
> **아키텍처**: Android 기기에서 MediaPipe + ONNX Runtime으로 완전 on-device 추론  
> **작성일**: 2026-05-28  
> **상태**: 미착수 (Option B 완료 후 검토)  
> **선행 조건**: `docs/ai-engine-migration-plan.md` Option B 분석 참고

---

## 개요

서버 AI 추론(FastAPI)을 제거하고 Android 기기에서 직접 낙상을 감지하는 아키텍처.  
네트워크 레이턴시 없이 추론하며 오프라인 동작이 가능하다.

---

## 아키텍처 비교

```
Option B (서버 추론)                    Option C (On-device 추론)
──────────────────────────             ──────────────────────────────
Android                                Android
  └─ landmark JSON → AI 서버             ├─ MediaPipe Android SDK
                                         │    → 관절 좌표 추출 (33개)
AI 서버 (FastAPI)                        ├─ 앱 메모리 (프레임 버퍼)
  ├─ Step 2~6 전처리                      ├─ Kotlin 파생변수 계산
  ├─ XGBoost 추론                         │    (각속도, 각가속도 등)
  └─ Spring Boot 이벤트 발행              ├─ ONNX Runtime 추론
                                         └─ score ≥ 70 시
Spring Boot                                Spring Boot 직접 HTTP
  └─ FCM, DB, Storage                 Spring Boot
                                         └─ FCM, DB, Storage

AI 서버 비용: $37~$75/월               AI 서버 비용: $0/월
추론 레이턴시: 네트워크 왕복            추론 레이턴시: < 10ms
오프라인 동작: ❌                       오프라인 동작: ✅
```

---

## Step별 파이프라인 호환성

### 전제: 모델은 단일 행(47피처) 입력

`main.py`의 XGBoost 모델은 30프레임 윈도우를 한꺼번에 받는 것이 아니라  
**프레임 1개 × 47피처** 단위로 추론하고 30개 결과를 평균 낸다.  
따라서 ONNX 변환 후 Android에서 프레임 단위 추론이 기술적으로 가능하다.

### Step 2 — NaN 보정

| 항목 | main.py (30프레임) | On-device (3프레임) |
|---|---|---|
| visibility 임계값 | < 0.3 → NaN | ✅ 동일 적용 가능 |
| 3σ 이상치 검출 | 30프레임 통계 기반 | ❌ 3프레임으로 통계 무의미 → **생략** |
| 보간 | cubic interpolation | ⚠️ 이전 프레임 값으로 단순 대체(ffill) |

> **영향 낮음**: MediaPipe Android SDK 자체 신뢰도가 높아 이상치 빈도 적음

### Step 3 — SGV 스무딩

| 항목 | main.py | On-device |
|---|---|---|
| 방식 | Savitzky-Golay window=7, poly=2 | ❌ 7프레임 불가 |
| 대안 A | — | window=3 SGV |
| 대안 B | — | EMA (지수이동평균, α=0.3) |

```kotlin
// 대안 B — EMA 구현 예시
class EmaFilter(private val alpha: Float = 0.3f) {
    private var prev: Float? = null
    fun filter(value: Float): Float {
        val out = prev?.let { alpha * value + (1 - alpha) * it } ?: value
        prev = out
        return out
    }
}
```

> ⚠️ **핵심 리스크**: 모델은 window=7 SGV로 스무딩된 피처로 학습됨.  
> 스무딩 방식 차이로 피처 분포가 달라져 **정확도 저하 가능성** 있음.  
> 재학습(경로 B) 없이 사용하면 실측 검증 필수.

### Step 4 — 골반 정규화

```kotlin
// Kotlin 구현 — 프레임 독립적, 완전 동일
val px = (kp23x + kp24x) / 2f
val py = (kp23y + kp24y) / 2f
val pz = (kp23z + kp24z) / 2f
// 모든 좌표에서 골반 중심 빼기
// scale = 두 골반 좌표 간 거리로 나누기
```

> ✅ **완전 호환**: 프레임 1개로 동일하게 계산 가능

### Step 5 — 피처 계산

#### 각도 (arctan2)

```kotlin
fun calcAngle(a: FloatArray, b: FloatArray, c: FloatArray): Float {
    val ba = floatArrayOf(a[0]-b[0], a[1]-b[1], a[2]-b[2])
    val bc = floatArrayOf(c[0]-b[0], c[1]-b[1], c[2]-b[2])
    val dot   = ba.zip(bc).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
    val cross = norm(crossProduct(ba, bc))
    return Math.toDegrees(atan2(cross.toDouble(), dot.toDouble())).toFloat()
}
```

> ✅ **완전 호환**

#### 각속도 (중앙차분, 3프레임)

```
버퍼: [frame_t-2, frame_t-1, frame_t]
각속도(t-1) = (angle_t - angle_t-2) / (2 * Δt)
```

> ✅ **가능**: 중간 프레임(t-1)의 각속도는 정확하게 계산됨

#### 각가속도 — 핵심 문제

```
3프레임 버퍼에서 각속도 배열 = [0, ω₁, 0]  (경계값 0)
각가속도 중앙차분 = (0 - 0) / 2Δt = 0  ← 항상 0
```

**해결: 각속도 링 버퍼 별도 유지**

```kotlin
// 각속도 히스토리를 별도 deque로 관리
val velocityHistory = ArrayDeque<FloatArray>(3)  // 15개 관절 각속도

// 매 프레임: 각속도 계산 후 히스토리에 추가
// 각가속도 = (ω[+1] - ω[-1]) / 2Δt  (히스토리의 중앙차분)
```

> ❌ → ✅ **링 버퍼 도입으로 해결 가능** (실질적으로 5프레임 정보 사용)

#### center 피처

```kotlin
val centerDist = norm(currentCenter - prevCenter)
val centerSpeed = centerDist / deltaT
```

> ✅ **완전 호환**: 2프레임으로 계산 가능

### Step 6 — StandardScaler

```kotlin
// scaler_params.json에서 로드한 mean, scale 적용
fun scale(features: FloatArray, mean: FloatArray, scale: FloatArray): FloatArray {
    return FloatArray(features.size) { i -> (features[i] - mean[i]) / scale[i] }
}
```

> ✅ **완전 호환**: 파라미터 추출 후 Kotlin 직접 적용

---

## 작업 목록

### Step C-1 — 모델 ONNX 변환 (Python, 1회)

```python
# scripts/convert_to_onnx.py 신규 생성
import joblib, json
from onnxmltools import convert_xgboost
from onnxmltools.utils import save_model
from skl2onnx.common.data_types import FloatTensorType

model  = joblib.load("pkl/xgb_model.pkl")
scaler = joblib.load("pkl/scaler.pkl")

# XGBoost → ONNX
onnx_model = convert_xgboost(
    model,
    initial_types=[('input', FloatTensorType([None, 47]))]
)
save_model(onnx_model, "xgb_model.onnx")

# StandardScaler 파라미터 추출
json.dump({
    "mean":          scaler.mean_.tolist(),
    "scale":         scaler.scale_.tolist(),
    "feature_names": scaler.feature_names_in_.tolist()
}, open("scaler_params.json", "w"), ensure_ascii=False)

print(f"ONNX 입력 shape: {onnx_model.graph.input[0].type}")
print("변환 완료")
```

출력 파일:
- `xgb_model.onnx` → Android `app/src/main/assets/` 에 배치
- `scaler_params.json` → Android `app/src/main/assets/` 에 배치

### Step C-2 — Android 의존성 추가

```kotlin
// android/app/build.gradle.kts
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
```

### Step C-3 — Kotlin 추론 엔진 구현

신규 파일: `android/app/src/main/java/com/example/on_safe/ai/FallDetector.kt`

구현 항목:
- `FrameBuffer` — `ArrayDeque(maxlen=3)` 좌표 버퍼
- `VelocityBuffer` — `ArrayDeque(maxlen=3)` 각속도 링 버퍼 (각가속도 계산용)
- `normalizeFrame()` — Step 4 골반 정규화
- `calcFeatures()` — Step 5 각도/각속도/각가속도/center 피처 47개
- `scaleFeatures()` — Step 6 StandardScaler 적용
- `infer()` — ONNX Runtime 추론 → score 반환

### Step C-4 — MediaPipe 연동

`CameraModeActivity.kt`에서 MediaPipe Pose 결과를 `FallDetector`에 전달.

```kotlin
// MediaPipe 결과 콜백
override fun onResults(result: PoseLandmarkerResult, ...) {
    val landmarks = result.landmarks()[0]  // 33개 NormalizedLandmark
    val score = fallDetector.process(landmarks, System.currentTimeMillis())
    if (score >= 70f) notifyFallEvent(score)
}
```

### Step C-5 — Spring Boot 직접 이벤트 발행

```kotlin
// AI 서버 우회, Spring Boot 직접 호출
suspend fun notifyFallEvent(score: Float) {
    api.postFallEvent(FallEventRequest(
        deviceId = deviceId,
        fallScore = score,
        level = "CRITICAL"
    ))
}
```

### Step C-6 — FastAPI AI 서버 제거

Option B에서 수정한 아래 파일들을 **삭제**:
- `app/ai/engine.py`
- `app/ai/buffer.py`
- `app/domain/camera/service.py`
- `app/domain/camera/router.py`
- `app/main.py`
- `requirements.txt` (Python AI 서버용)

Spring Boot `InternalController.kt`에서 fall-event 수신 엔드포인트만 남김.

---

## 정확도 검증 계획

Option C 적용 후 반드시 아래 측정 필요.

| 항목 | 기준 | 방법 |
|---|---|---|
| 추론 레이턴시 | < 10ms/프레임 | Android Profiler 측정 |
| 실제 낙상 감지율 | Option B 대비 ≥ 90% | 동일 테스트 영상으로 비교 |
| 오탐률 | Option B 대비 ≤ 2배 | 정상 동작 30분 모니터링 |
| 배터리 소모 | < 10%/시간 추가 | 배터리 통계 비교 |

---

## 경로 선택

### 경로 A — 현재 모델 그대로 ONNX 변환 (단기)

```
xgb_model.pkl → ONNX 변환 → Android 배포
SGV window=3 또는 EMA 적용 (학습 불일치 허용)
각속도 링 버퍼로 각가속도 복원
```

- 재학습 없이 빠르게 적용
- 정확도 저하 가능 → 실측 검증 필수

### 경로 B — 3프레임 기준 재학습 (중기, 권장)

```
DataScience 노트북 수정:
  - SGV window → 3 또는 EMA
  - 각가속도 계산 → 링 버퍼 방식으로
모델 재학습 → ONNX 변환 → Android 배포
```

- 학습·추론 파이프라인 완전 일치
- 가장 높은 정확도 보장

---

## 비용 비교 (500명 기준)

| 항목 | Option B + 3fps 릴레이 | Option C |
|---|---|---|
| AI 서버 | $37/월 | **$0** |
| 보호자 릴레이 | $1,313/월 | 동일 (별도 선택) |
| **월 합계** | **≈ $1,350** | **≈ $1,313** |

> AI 서버 비용($37)은 전체 대비 작으나, 서버리스 구조 단순화와  
> 오프라인 동작 가능, 레이턴시 개선이 Option C의 주요 이점이다.

---

## 참고 파일

| 파일 | 역할 |
|---|---|
| `docs/ai-engine-migration-plan.md` | Option A/B 서버 추론 마이그레이션 계획 |
| `OnSafe/ai-server/main.py` | 전처리 파이프라인 소스 오브 트루스 |
| `OnSafe/ai-server/pkl/xgb_model.pkl` | ONNX 변환 대상 모델 |
| `OnSafe/ai-server/pkl/scaler.pkl` | 파라미터 추출 대상 |
| `OnSafe/DataScience/Modeling/Make_AI.ipynb` | 재학습 시 수정 기준 노트북 |
| `scripts/convert_to_onnx.py` | ONNX 변환 스크립트 (신규 생성 예정) |
| `android/.../ai/FallDetector.kt` | Kotlin 추론 엔진 (신규 생성 예정) |
