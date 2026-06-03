# AI 마이그레이션 테스트 보고서

> **브랜치**: `feature/ai-engine-migration`
> **테스트 일시**: 2026-05-29
> **테스트 범위**: Step 1~6 전체 (WebSocket 전환 + engine.py landmark 입력 변경 + 레거시 정리)

---

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 플랫폼 | Windows 11 + Docker Desktop |
| 컨테이너 | `python-ai` (FastAPI :8000) + `redis` (:6379) |
| Kotlin 서버 | 미기동 (내부 API 호출은 fire-and-forget으로 실패 흡수) |
| 테스트 JWT | `.env` `JWT_SECRET`으로 직접 생성한 테스트 토큰 사용 |

---

## 테스트 방법

### Level 1 — Docker 빌드 검증

Dockerfile.python 변경사항(apt-get 레이어 제거, pip install 단순화) 및 requirements.txt(xgboost 추가, mediapipe/opencv 제거) 검증.

```bash
docker compose build python-ai
```

### Level 2 — 서버 기동 검증

ImportError, 모델 로드 오류 없이 기동되는지 확인.

```bash
docker compose up -d redis python-ai
docker compose logs python-ai
```

### Level 3 — engine.py 단위 테스트

WebSocket/서버 없이 `infer_landmarks()` 직접 호출. 컨테이너 내부에서 실행.

```bash
docker compose exec python-ai python -c "..."
```

### Level 4 — WebSocket 통합 테스트

유효한 JWT로 WebSocket 연결, init → frame × 40 → result 수신 확인.

```bash
python -c "asyncio.run(websocket_test())"
```

### 프로브 테스트

- 잘못된 JWT로 연결 시도 → 403 거부 확인
- WINDOW_SIZE 미달 구간 score=0.0 확인
- STRIDE 주기 정확성 확인

---

## 테스트 결과

### Level 1 — Docker 빌드 ✅

```
#9 [4/6] RUN pip install --no-cache-dir -r requirements.txt
...
Successfully installed ... xgboost-3.2.0 ...
Image on-safe-backend-python-ai Built
```

- mediapipe, opencv-python 설치 없음 확인
- xgboost-3.2.0 정상 설치
- apt-get 시스템 라이브러리 레이어 제거로 이미지 경량화

### Level 2 — 서버 기동 ✅

```
INFO: Started server process [1]
INFO: Waiting for application startup.
INFO: Application startup complete.
INFO: Uvicorn running on http://0.0.0.0:8000
```

- ImportError 없음 (`infer_frame_async` 제거 반영 정상)
- 모델/스케일러 로드 성공

### Level 3 — engine.py 단위 테스트 ✅

```
engine.py OK WINDOW=30 STRIDE=5 FEATURES=47
SKIP   frame=0  ~ frame=28  (윈도우 미달)
RESULT frame=29 score=95.82 fall=True keys=47
SKIP   frame=30 ~ frame=33
RESULT frame=34 score=98.48 fall=True keys=47
SKIP   frame=35 ~ frame=38
RESULT frame=39 score=97.57 fall=True keys=47

total results: 3
PASS engine unit test
```

| 검증 항목 | 결과 |
|---|---|
| WINDOW_SIZE=30 충족 전 추론 없음 | ✅ frame 0~28 SKIP |
| 최초 추론 시점 (frame 29) | ✅ 정확 |
| STRIDE=5 주기 (29, 34, 39) | ✅ 정확 |
| FEATURE_COLUMNS=47개 반환 | ✅ keys=47 |

### Level 4 — WebSocket 통합 테스트 ✅

**프로브 — 잘못된 JWT 거부**
```
INFO: WebSocket /ws/stream?token=invalid_token  403 Forbidden
INFO: connection rejected (403 Forbidden)
```
→ ✅ 인증 실패 시 연결 거부

**정상 흐름 — init → frame × 40**
```
OK init_ok
SKIP  frame=0  ~ frame=28  score=0.00
INFER frame=29 score=96.38 fall=True level=위험
SKIP  frame=30 ~ frame=33
INFER frame=34 score=94.02 fall=True level=위험
SKIP  frame=35 ~ frame=38
INFER frame=39 score=96.19 fall=True level=위험
```

| 검증 항목 | 결과 |
|---|---|
| JWT 인증 → init_ok 수신 | ✅ |
| 매 frame 응답 수신 | ✅ |
| WINDOW 미달 구간 score=0.0 | ✅ |
| 추론 주기 정확성 (29, 34, 39) | ✅ |
| level 필드 반환 | ✅ (버그 수정 후) |

---

## 발견된 이슈 및 조치

### 이슈 1 — `level=None` 버그 (수정 완료)

| 항목 | 내용 |
|---|---|
| 현상 | WebSocket result 메시지의 `level` 필드가 항상 `null` |
| 원인 | `StreamResponse`에 `level` 필드 누락 |
| 수정 | `schemas.py` StreamResponse에 `level: Optional[str] = None` 추가, `service.py` `process_frame()` 반환값에 level 포함, `router.py` `hasattr` 조건 제거 |

### 이슈 2 — `InconsistentVersionWarning` (미수정, 모니터링 필요)

| 항목 | 내용 |
|---|---|
| 현상 | `sklearn 1.6.1`로 학습된 scaler.pkl을 `sklearn 1.8.0`으로 로드 시 경고 |
| 위험도 | 낮음 (현재 추론 결과 정상) |
| 권장 조치 | scaler.pkl 재생성 (sklearn 1.8.0 환경에서 재학습 후 교체) |

### 이슈 3 — `/health` 엔드포인트 없음 ✅ 수정 완료 (PR #12)

| 항목 | 내용 |
|---|---|
| 현상 | `GET /health` → 404 |
| 조치 | `app/main.py` startup에서 `_load_models()` eager load 추가, `GET /health` 엔드포인트 추가 (`model_loaded`, `scaler_loaded` 상태 반환) |
| 결과 | `GET /health` → `{"status": "ok", "model_loaded": true, "scaler_loaded": true}` |
| 추가 | `docker-compose.yml` python-ai healthcheck 연동 (GET /health, 15초 주기, start_period 20초) |

### 이슈 4 — JWT 거부 방식 (403 vs 1008)

| 항목 | 내용 |
|---|---|
| 현상 | 잘못된 JWT 시 WebSocket close code 1008 대신 HTTP 403 반환 |
| 원인 | `websocket.close(1008)` 를 `websocket.accept()` 전에 호출 시 Starlette가 HTTP 403으로 처리 |
| 영향 | 기능상 문제 없음 (연결 거부는 동일) — 클라이언트 에러 처리 방식에만 영향 |

---

## 테스트 스크립트 위치

| 스크립트 | 용도 |
|---|---|
| `scripts/test_engine.py` | engine.py 단위 테스트 (서버 불필요) |
| `scripts/test_ws_stream.py` | WebSocket 통합 테스트 |

```bash
# 단위 테스트
python scripts/test_engine.py

# WebSocket 통합 테스트
python scripts/test_ws_stream.py \
  --token {jwt_token} \
  --user {user_id} \
  --device {device_id}
```
