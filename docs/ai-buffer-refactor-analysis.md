# buffer.py 리팩터링 분석 — should_infer() 처리 방안
> **연관 작업**: `docs/ai-engine-migration-plan.md` **Step 3** — buffer.py 수정 + service.py 연동 정리  
> **작성일**: 2026-05-28  
> **상태**: ✅ Step 3 구현 완료 (방안 A 적용)  
> **결론**: 방안 A (engine.py 단일 책임) 채택 — `push_frame_count()` / `should_infer()` 제거, deque가 추론 타이밍 단일 판단

---

## 배경

`engine.py`를 30프레임 윈도우 기반으로 재작성하면  
`buffer.py`의 Redis 카운터 방식과 **역할이 겹치는 문제**가 발생한다.

### 현재 ASIS 구조

```
service.py
  ├─ push_frame_count(device_id)   # ① Redis 카운터 +1  (buffer.py)
  ├─ should_infer(device_id)       # ② Redis 카운터 >= 30? (buffer.py)
  │    └─ False → 조기 반환
  └─ infer_frame_async()           # ③ 조건 충족 시 추론  (engine.py)
```

```python
# buffer.py — Redis 기반 카운터
async def push_frame_count(device_id: str) -> int:
    key = f"frame_count:{device_id}"
    count = await r.incr(key)
    await r.expire(key, 10)          # TTL 10초
    return count

async def should_infer(device_id: str, threshold: int = 30) -> bool:
    val = await r.get(f"frame_count:{device_id}")
    return val is not None and int(val) >= threshold
```

**현재 "준비 여부" 판단 주체: Redis 카운터 (buffer.py)**

---

## 방안 비교

### 방안 B — Redis 카운터 + engine.py deque 공존 (비권장)

```
service.py
  ├─ push_frame_count()   ← Redis 카운터  (buffer.py)
  ├─ should_infer()       ← Redis 카운터 체크 (buffer.py)
  └─ infer_frame_async()
       └─ engine.py
            └─ deque(maxlen=30)      ← 인메모리 버퍼
                 └─ len(buf) < 30?  ← 두 번째 체크 (중복)
```

#### 문제 1 — 서버 재시작 시 불일치

```
Redis 카운터: 이전 세션 값 35 (TTL 미만료)
engine.py deque: 0개 (인메모리 초기화)

결과:
  should_infer() → True  (Redis 35 >= 30)
  engine.py      → len == 0 → 조기 반환
  → should_infer가 True인데 추론 실패 (모순)
```

#### 문제 2 — TTL 10초 vs 인메모리 영구 보존 불일치

```
10초 동안 프레임 없다가 재개 시:
  Redis: TTL 만료 → 카운터 0 리셋 → should_infer() = False
  deque: 이전 30개 그대로 유지 → 실제 추론 가능 상태

결과: 추론이 불필요하게 막힘
```

#### 문제 3 — 소스 오브 트루스 부재

```
"30프레임이 쌓였는가?"를 판단하는 주체가 두 곳
  → WINDOW_SIZE 변경 시 두 곳 모두 수정 필요
  → 코드 가독성 저하, 유지보수 난이도 증가
```

---

### 방안 A — engine.py 단일 책임 (채택)

```
service.py
  └─ infer_frame_async()
       └─ engine.py
            └─ deque(maxlen=30)       ← 유일한 판단 주체
                 ├─ len(buf) < 30?   → {"score": 0.0} 반환
                 ├─ count % STRIDE?  → {"score": 0.0} 반환
                 └─ 조건 충족        → step2~6 → 추론
```

#### engine.py 변경

```python
_frame_buffers: dict[str, deque] = {}
_frame_counts:  dict[str, int]   = {}

def infer_frame(jpeg_bytes: bytes, device_id: str) -> dict:
    # ... MediaPipe 추출 ...

    buf = _get_buffer(device_id)
    buf.append(raw)
    _frame_counts[device_id] = _frame_counts.get(device_id, 0) + 1

    # 준비 여부 판단 — engine.py 한 곳에서만
    if len(buf) < WINDOW_SIZE:
        return {"score": 0.0, "fall": False}
    if _frame_counts[device_id] % STRIDE != 0:
        return {"score": 0.0, "fall": False}

    # step2~6 실행
    df_win = pd.DataFrame(buf)
    df_win = step2_resolve_nan(df_win)
    df_win = step3_smoothing_savgol(df_win)
    df_win = step4_pose_normalize(df_win)
    df_win = step5_make_features(df_win)
    X = step6_scale(df_win)

    proba = model.predict_proba(X)
    score = float(proba[:, 1].mean() * 100)
    fall  = bool(score >= CRITICAL_THRESHOLD)
    return {"score": score, "fall": fall}
```

#### service.py 변경

```python
# Before
async def process_stream(jpeg_bytes, user_id, device_id):
    await push_frame_count(device_id)      # 제거
    await save_latest_frame(...)
    await _publish_frame(...)
    ready = await should_infer(device_id)  # 제거
    if not ready:
        return StreamResponse(score=0.0)

    result = await infer_frame_async(jpeg_bytes, device_id)
    ...

# After
async def process_stream(jpeg_bytes, user_id, device_id):
    await save_latest_frame(device_id, jpeg_bytes)  # 유지
    await _publish_frame(user_id, jpeg_bytes)       # 유지

    result = await infer_frame_async(jpeg_bytes, device_id)
    # engine.py 내부에서 window/stride 판단 완료
    # score == 0.0이면 조기 반환 (window 미달)
    if result["score"] == 0.0 and not result["fall"]:
        return StreamResponse(score=0.0, fall=False)
    ...
```

#### buffer.py 변경

```python
# 제거 대상
async def push_frame_count(device_id: str) -> int: ...  # 삭제
async def should_infer(device_id: str, ...) -> bool: ...  # 삭제
# Redis 키 frame_count:{device_id} 미사용

# 유지 대상
async def save_score(user_id, score, level): ...        # 유지
async def get_score(user_id): ...                       # 유지
async def save_latest_frame(device_id, jpeg_bytes): ... # 유지
async def get_latest_frame(device_id): ...              # 유지
async def check_caution_cooldown(user_id, ...): ...     # 유지
```

---

## 최종 비교표

| 항목 | 방안 A (채택) | 방안 B |
|---|---|---|
| 준비 여부 판단 주체 | engine.py deque **1곳** | Redis + deque **2곳** |
| 서버 재시작 시 | deque 초기화 → 자연 재누적 | Redis/deque 불일치 가능 |
| TTL 만료 시 | 영향 없음 | Redis 리셋 → 추론 지연 |
| WINDOW_SIZE 변경 | engine.py 상수 1곳만 | Redis threshold도 함께 수정 |
| Redis 의존성 | 추론 판단에서 **완전 분리** | 추론 판단도 Redis 의존 |
| 코드 복잡도 | 낮음 | 높음 |
| 예외 상황 안전성 | 높음 | 낮음 |

---

## 역할 분리 원칙

```
Redis (buffer.py) 담당:           engine.py 담당:
  - 점수 캐시 (score:{user_id})     - 프레임 윈도우 누적
  - 최신 프레임 저장                - 추론 실행 시점 결정
  - 쿨다운 플래그                   - step2~6 전처리
                                    - 모델 추론

"공유 상태 저장" → Redis           "실시간 제어 흐름" → 인메모리
```

Redis는 **공유 상태 저장**(여러 요청 간 공유, TTL 기반 만료)에 적합하고,  
**추론 제어**(몇 프레임 쌓였나, stride 체크)는 인메모리 deque가 더 적합하다.  
두 역할을 섞으면 재시작·TTL 만료 등 예외 상황에서 불일치가 발생한다.

---

## 참고 파일

| 파일 | 변경 여부 |
|---|---|
| `app/ai/buffer.py` | `push_frame_count`, `should_infer` 제거 |
| `app/ai/engine.py` | `_frame_buffers`, `_frame_counts` deque 추가 |
| `app/domain/camera/service.py` | `push_frame_count`, `should_infer` 호출 제거 |
