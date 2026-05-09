# 카메라 실시간 스트리밍 구현 사항

**작성일:** 2026-05-09  
**브랜치:** feature/camera-streaming  
**담당:** On-Safe Backend

---

## 개요

기존 On-Safe 백엔드에 실시간 카메라 스트리밍 기능을 추가했습니다.  
카메라 기기가 JPEG 프레임을 서버에 전송하면, Redis pub/sub을 거쳐 WebSocket으로 연결된 보호자 클라이언트에게 실시간으로 전달됩니다.

---

## 전체 데이터 흐름

```
카메라 기기 (IP Camera / 스마트폰)
        │
        ▼ RTSP
Python AI 서버 (FastAPI + FFmpeg)
        │ ① JPEG 프레임 추출
        │ POST /internal/frame/{userId}   ──→  Redis PUBLISH camera:frames:{userId}
        │ ② 낙상 감지 시 POST /internal/realtime
        ▼
Kotlin Spring Backend (WebFlux)
        │ Redis SUBSCRIBE camera:frames:{userId}
        │ WebSocket /ws/camera/{userId}
        ▼
Android / Web 클라이언트
```

---

## 카메라 세션 상태 전환

```
[촬영 시작하기 버튼 클릭]
        │
        ▼
 PUT /api/camera/session/{deviceId}/start
        │  → Redis에 status=CONNECTING 저장
        ▼
 CONNECTING (연결 중... UI)
        │
        ▼  WebSocket 연결 후 첫 프레임 수신
 CameraStreamWebSocketHandler.markLive()
        │  → Redis에 status=LIVE 업데이트
        ▼
 LIVE (● LIVE 배지 + 전송 중 UI)
        │
        ▼
[촬영 종료하기 버튼 클릭]
        │
 PUT /api/camera/session/{deviceId}/stop
        │  → Redis 세션 키 삭제
        ▼
 STANDBY (카메라 대기 중 UI)
```

---

## 신규 파일 목록

| 파일 | 설명 |
|------|------|
| `config/RedisConfig.kt` | ByteArray Redis 템플릿 + ReactiveRedisMessageListenerContainer 빈 등록 |
| `config/WebSocketConfig.kt` | `/ws/camera/**` → `CameraStreamWebSocketHandler` 라우팅 |
| `domain/camera/websocket/CameraStreamWebSocketHandler.kt` | Redis pub/sub 구독 → WebSocket 바이너리 프레임 전달, 첫 프레임 수신 시 LIVE 전환 |
| `domain/camera/model/entity/CameraSessionStatus.kt` | `STANDBY` / `CONNECTING` / `LIVE` 열거형 |
| `domain/camera/model/dto/CameraSessionResponse.kt` | 세션 상태 응답 DTO (userId, status, startedAt, elapsedSeconds) |
| `domain/camera/service/CameraSessionService.kt` | Redis 기반 세션 상태 관리 (start/stop/markLive/getStatus) |
| `domain/camera/controller/CameraSessionController.kt` | 세션 REST 엔드포인트 (start/stop/status) |

---

## 수정된 파일 목록

| 파일 | 변경 내용 |
|------|----------|
| `config/SecurityConfig.kt` | `publicPaths`에 `/ws/camera/**` 추가 (WebSocket 업그레이드 요청 인증 제외) |
| `domain/camera/repository/DeviceRepository.kt` | `findUserIdByDeviceId(deviceId)` 메서드 추가 (Firestore `devices` 컬렉션 조회) |
| `domain/internal/service/InternalService.kt` | `byteArrayRedisTemplate` 주입 및 `publishFrame(userId, frame)` 추가 |
| `domain/internal/controller/InternalController.kt` | `POST /internal/frame/{userId}` 엔드포인트 추가 (octet-stream) |
| `docker-compose.yml` | `rtsp-server` (mediamtx) 서비스 추가 — 에뮬레이터 테스트용 |

---

## 신규 API 엔드포인트

### REST API (JWT 인증 필요)

#### 세션 시작
```
PUT /api/camera/session/{deviceId}/start

Response 200:
{
  "success": true,
  "data": {
    "userId": "user_abc",
    "status": "CONNECTING",
    "startedAt": "2026-05-09T10:00:00Z",
    "elapsedSeconds": null
  }
}
```

#### 세션 종료
```
PUT /api/camera/session/{deviceId}/stop

Response 200:
{
  "success": true,
  "message": "촬영이 종료되었습니다."
}
```

#### 세션 상태 조회 (클라이언트 폴링)
```
GET /api/camera/session/{userId}/status

Response 200:
{
  "success": true,
  "data": {
    "userId": "user_abc",
    "status": "LIVE",          // STANDBY | CONNECTING | LIVE
    "startedAt": "2026-05-09T10:00:00Z",
    "elapsedSeconds": 125
  }
}
```

### 내부 API (JWT 없음, 로컬 네트워크 전용)

#### 프레임 퍼블리시
```
POST /internal/frame/{userId}
Content-Type: application/octet-stream
Body: <JPEG 바이너리>

Response 200:
{
  "success": true,
  "message": "프레임 전송 완료"
}
```

### WebSocket

```
ws://server:8080/ws/camera/{userId}

- 연결 후 JPEG 프레임을 Binary Message로 수신
- 프레임 수신 없이 연결만 해도 세션 상태는 유지됨
- 서버에서 끊기면 재연결 로직 클라이언트 측에서 구현 필요
```

---

## Redis 키 구조

| 키 | 값 | TTL |
|----|-----|-----|
| `camera:session:{userId}:status` | `STANDBY` \| `CONNECTING` \| `LIVE` | 12시간 |
| `camera:session:{userId}:started_at` | ISO-8601 문자열 (예: `2026-05-09T10:00:00Z`) | 12시간 |
| `camera:frames:{userId}` | pub/sub 채널 (영구 키 아님) | — |

---

## 에뮬레이터 테스트 방법

### 1단계 — docker-compose 실행

```bash
docker-compose up -d
```

`rtsp-server` (mediamtx, 포트 8554)가 함께 실행됩니다.

### 2단계 — 테스트 영상을 RTSP로 스트리밍

```bash
# PC에 FFmpeg가 설치된 경우
ffmpeg -re -stream_loop -1 -i test.mp4 \
  -c:v libx264 -f rtsp rtsp://localhost:8554/test
```

### 3단계 — Python AI 서버가 RTSP 소스를 사용하도록 설정

Python 서버의 카메라 URL을 `rtsp://rtsp-server:8554/test`로 등록합니다:

```bash
curl -X PUT http://localhost:8080/api/camera/url/{deviceId} \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"cameraUrl": "rtsp://rtsp-server:8554/test"}'
```

### 4단계 — 안드로이드 에뮬레이터에서 접속

```kotlin
// 에뮬레이터에서 PC의 localhost는 10.0.2.2로 접근
const val BASE_URL = "http://10.0.2.2:8080"
const val WS_URL   = "ws://10.0.2.2:8080/ws/camera/{userId}"
```

### 5단계 — 세션 시작 및 WebSocket 연결 흐름

```
1. PUT http://10.0.2.2:8080/api/camera/session/{deviceId}/start
   → UI: "연결 중..."
2. WebSocket 연결: ws://10.0.2.2:8080/ws/camera/{userId}
3. GET http://10.0.2.2:8080/api/camera/session/{userId}/status  (1초 폴링)
   → status가 LIVE로 변경되면 UI: "● LIVE"
4. 바이너리 메시지 수신 → Image 위젯에 렌더링
```

---

## Python AI 서버 측 연동 코드 (참고)

> **중요:** Spring Data Redis 3.4.x `ReactiveRedisMessageListenerContainer`는 String 메시지만 지원합니다.  
> JPEG 바이트를 **Base64로 인코딩**한 뒤 Redis에 발행해야 합니다.

### Redis pub/sub 직접 사용 (권장)

```python
import redis
import cv2
import base64

r = redis.Redis(host='redis', port=6379)
cap = cv2.VideoCapture("rtsp://rtsp-server:8554/test")

while True:
    ret, frame = cap.read()
    if not ret:
        break
    _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 70])
    # Base64 인코딩 필수 — Kotlin 수신 측에서 디코딩 후 WebSocket 바이너리 전송
    r.publish(f"camera:frames:{user_id}", base64.b64encode(jpeg.tobytes()).decode('utf-8'))
```

### HTTP 엔드포인트 사용 (테스트용)

```python
import requests
import cv2

cap = cv2.VideoCapture(0)

while True:
    ret, frame = cap.read()
    if not ret:
        break
    _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 70])
    # 서버 측에서 Base64 변환 처리 — 클라이언트는 raw bytes 전송
    requests.post(
        f"http://kotlin-api:8080/internal/frame/{user_id}",
        data=jpeg.tobytes(),
        headers={"Content-Type": "application/octet-stream"}
    )
```

---

## 주요 기술 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| 스트리밍 방식 | WebSocket (Binary) | 에뮬레이터 호환, 구현 단순, 1~3초 지연 |
| 프레임 전달 방식 | Redis pub/sub + Base64 | Spring Data Redis 3.4.x String 제약으로 Base64 인코딩 필수 |
| 세션 상태 저장 | Redis (TTL 12h) | 빠른 읽기/쓰기, 자동 만료, Firestore 부하 없음 |
| WebSocket 인증 | 미적용 (publicPath) | WebSocket 핸드셰이크 시 헤더 전달 제약, 추후 쿼리 파라미터 토큰 방식으로 보완 가능 |
| 테스트 서버 | mediamtx | 영상 파일 → RTSP 변환, Docker 이미지 제공, 설정 불필요 |

---

## 향후 개선 사항

- [ ] WebSocket 연결 시 쿼리 파라미터 토큰(`?token=...`) 검증 추가
- [ ] 프레임 전송 FPS 조절 옵션 (현재 카메라 원본 FPS 그대로 전송)
- [ ] 다중 보호자 동시 시청 지원 (현재 구조에서 pub/sub 특성상 이미 지원됨)
- [ ] WebSocket 재연결 로직 (Android 클라이언트 측)
- [ ] mediamtx를 `docker-compose --profile test`로 분리하여 프로덕션 환경에서 자동 제외
