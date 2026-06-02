# AI 서버 런타임 분석 — 리소스·잠재 이슈

> **기준**: Step 5~6 완료 후 WebSocket+landmark 아키텍처 (2026-05-29)
> **범위**: Python AI 서버 (FastAPI :8000) 단독 분석

---

## 1. 병목 (Bottleneck)

### 1-1. XGBoost 추론 — CPU 스레드 풀

```
infer_landmarks_async()
  └─ loop.run_in_executor(None, infer_landmarks, ...)
       └─ Step2~6 전처리 + model.predict_proba(X)  ← CPU bound
```

- `run_in_executor`는 기본 `ThreadPoolExecutor`를 사용
- 기본 스레드 수: `min(32, os.cpu_count() + 4)`
- **문제**: 동시 WebSocket 연결이 많으면 스레드 풀 포화 → 추론 대기 큐 적체
- 단일 추론 소요 시간: Step2~6(pandas 연산) + XGBoost ≈ 50~200ms (CPU 사양에 따라)

**권장 조치**:
```python
# 명시적 스레드 풀 크기 지정
from concurrent.futures import ThreadPoolExecutor
_executor = ThreadPoolExecutor(max_workers=4)
await loop.run_in_executor(_executor, infer_landmarks, ...)
```

---

### 1-2. Firestore 쓰기 — `_save_realtime_data()` STRIDE마다 호출

```
매 STRIDE(5) 프레임마다:
  _save_realtime_data() → Firestore col.add(data)   ← 네트워크 I/O
  _update_realtime()    → POST /internal/realtime    ← 네트워크 I/O
```

- 30fps 기준 STRIDE=5 → **초당 6회** Firestore 쓰기 + Kotlin 호출
- 기기 N대 동시 → **N × 6회/초** Firestore 쓰기
- Firestore 무료 할당량: 쓰기 20,000건/일 → **기기 1대 = 약 518,400건/일** (초과)

**권장 조치**:
1. `_save_realtime_data()` 호출 주기 확대 (STRIDE × K배마다 1회)
2. 또는 realtime_data Firestore 저장 제거 — Redis `save_score()`로 대체 (Android가 Python `/api/camera/score` 직접 조회)

---

### 1-3. `_save_realtime_data()` 최신 2000개 유지 쿼리

```python
old_docs = await col.where(...).order_by(...).offset(2000).get()
for doc in old_docs:
    await doc.reference.delete()
```

- `offset(2000)` 쿼리: Firestore는 offset 이전 문서를 모두 읽은 후 버림 → **읽기 과금 폭증**
- 문서 수가 늘수록 비용·레이턴시 선형 증가

**권장 조치**: `createdAt` 타임스탬프 기반 TTL 필드 + Cloud Scheduler 배치 정리 방식 전환

---

## 2. 쓰로틀링 (Throttling)

### 2-1. Kotlin `/internal/realtime` 호출 빈도

```
현재: 추론마다 POST /internal/realtime
문제: Kotlin 서버 과부하 → 응답 지연 → process_frame() 전체 지연
```

- `httpx.AsyncClient`를 매 호출마다 생성·소멸 (`async with httpx.AsyncClient()`)
- **연결 풀 없음** → TCP 핸드셰이크 반복 오버헤드

**권장 조치**:
```python
# 앱 시작 시 싱글턴 클라이언트 생성
_http_client: httpx.AsyncClient | None = None

@app.on_event("startup")
async def startup():
    global _http_client
    _http_client = httpx.AsyncClient(timeout=3.0)
```

---

### 2-2. Firebase Storage 업로드 (낙상 감지 시)

- 낙상 감지 → `upload_thumbnail()` → GCS HTTP 업로드
- 동시 낙상 N건 → N개 업로드 동시 실행 → 대역폭 포화 가능성
- 현재 코드에 업로드 동시성 제한 없음

**권장 조치**: 세마포어로 동시 업로드 수 제한
```python
_upload_semaphore = asyncio.Semaphore(3)
async with _upload_semaphore:
    image_url = await upload_thumbnail(log_id, jpeg_bytes)
```

---

## 3. TTL 이슈

### 현재 Redis TTL 구조

| 키 | TTL | 만료 시 동작 |
|---|---|---|
| `score:{user_id}` | 30s | GET /score → score=0.0, level="정상" 반환 |
| `caution_cd:{user_id}` | 300s (5분) | 쿨다운 해제 → WARNING 재발송 허용 |
| `frame:{device_id}` | 5s | 미사용 (보호자 릴레이 보류) |

### 잠재 이슈

**`score:{user_id}` TTL=30s**
```
문제: 추론 주기 = STRIDE/FPS = 5/30 ≈ 0.17s
      → 30초 내 재추론으로 갱신되므로 TTL 만료는 정상 범위
      그러나: 카메라 연결 끊김 후 30초간 마지막 점수 유지 → 오해 가능
권장: 카메라 세션 종료 시 score 키 명시적 삭제 (또는 TTL 단축 5~10s)
```

**`caution_cd:{user_id}` NX 방식**
```
문제: Redis 장애/재시작 시 쿨다운 키 소멸 → 쿨다운 리셋
     → 장애 직후 WARNING 이벤트 중복 발송 가능
영향: 낮음 (알림 과다 발송이 미발송보다 낫다는 정책적 판단 가능)
```

---

## 4. 동시성 (Concurrency)

### 4-1. engine.py `_frame_buffers` — 글로벌 dict, 잠금 없음

```python
_frame_buffers: dict[str, deque] = {}  # 전역 변수
_frame_counts:  dict[str, int]   = {}

def infer_landmarks(landmarks, device_id, timestamp) -> dict:
    buf = _get_buffer(device_id)
    buf.append(raw)                         # deque append
    _frame_counts[device_id] += 1           # dict 업데이트
```

- Python GIL이 dict/deque 단일 연산을 원자적으로 보호
- **문제**: 같은 `device_id`로 여러 WebSocket 연결이 동시에 추론 호출 시 버퍼 오염
- 실제 발생 조건: 한 기기에서 WebSocket을 중복 연결 (재연결 중 overlap 구간)

**권장 조치**: `device_id`별 `asyncio.Lock` 추가 또는 연결당 로컬 버퍼 사용 (main.py 방식)

---

### 4-2. WebSocket 연결당 독립 버퍼 vs 글로벌 버퍼

```
main.py 방식:  연결당 로컬 deque → 재연결 시 버퍼 자동 초기화 ✅
현재 방식:     device_id별 글로벌 deque → 재연결 후 기존 버퍼 이어받음 ⚠️
```

기존 버퍼 이어받기는 재연결 후 빠른 추론 재개라는 장점이 있으나,
이전 세션의 오래된 프레임이 새 세션 추론에 영향을 줄 수 있습니다.

**권장 조치**: `init` 메시지 수신 시 해당 `device_id` 버퍼 초기화

```python
if msg_type == "init":
    user_id = data.get("user_id")
    device_id = data.get("device_id")
    # 버퍼 초기화
    from app.ai.engine import _frame_buffers, _frame_counts
    _frame_buffers.pop(device_id, None)
    _frame_counts.pop(device_id, None)
    await websocket.send_json({"type": "init_ok"})
```

---

## 5. 트랜잭션 (Transaction)

### 5-1. fall-log 저장 + FCM 알림 비원자성

```
Python → POST /internal/fall-log
              ↓
Kotlin: fallLogRepository.save()    ← DB 저장
        sendNotificationSafe()      ← FCM 발송 (독립)
```

- DB 저장 성공 + FCM 실패 → 로그는 있으나 알림 없음 (현재 허용 설계)
- DB 저장 실패 + FCM 성공 → 알림 왔으나 로그 없음 (발생 불가: FCM은 DB 이후)
- **현재 설계**: `sendNotificationSafe()`가 FCM 실패를 흡수하므로 DB 저장은 항상 보장

---

### 5-2. `_save_realtime_data()` + `_update_realtime()` 비원자성

```
_save_realtime_data() → Firestore realtime_data 컬렉션 추가
_update_realtime()    → Kotlin → realtime_data/{userId} 문서 덮어쓰기
```

- 두 쓰기가 독립적으로 실행 → 하나 실패해도 다른 하나는 성공
- **문제**: Firestore에 두 개의 상이한 데이터 소스 (컬렉션 문서 vs 단일 문서)가 일시적 불일치 가능
- 실용적 영향: 낮음 (realtime 단일 문서가 최신값을 덮어씀)

---

## 6. 리소스 산정 요약

### 기기 N대 동시 운영 시

| 리소스 | 수식 | 기기 10대 | 기기 50대 | 비고 |
|---|---|---|---|---|
| CPU 스레드 | N (동시 추론) | 10 | 50 | 추론 겹치면 대기 |
| 메모리 (deque) | N × 30프레임 × ~2KB | ~600KB | ~3MB | 무시 가능 |
| Redis 쓰기/초 | N × 6 (STRIDE=5, 30fps) | 60건/초 | 300건/초 | score TTL 30s |
| Firestore 쓰기/일 | N × 6 × 86400 | 518만건 | 2590만건 | 무료 한도 초과 |
| Kotlin 호출/초 | N × 6 × 2 (realtime+falllog) | 120건/초 | 600건/초 | falllog는 이벤트만 |
| GCS 업로드 | 낙상 이벤트당 1건 | 이벤트 빈도에 따라 | | 비동기 처리 |

### 권장 최적화 우선순위

| 우선순위 | 항목 | 예상 효과 |
|---|---|---|
| 🔴 즉시 | httpx 싱글턴 클라이언트 | 매 호출 TCP 연결 제거 |
| 🔴 즉시 | `_save_realtime_data()` 호출 빈도 조정 | Firestore 비용 대폭 절감 |
| 🟡 단기 | ThreadPoolExecutor 크기 명시 | 추론 대기 예측 가능 |
| 🟡 단기 | init 시 device_id 버퍼 초기화 | 재연결 안정성 |
| 🟢 중기 | GCS 업로드 세마포어 | 동시 낙상 대량 발생 대비 |
| 🟢 중기 | realtime_data 정리 방식 변경 | Firestore 읽기 비용 절감 |
