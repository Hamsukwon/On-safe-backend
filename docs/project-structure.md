# On-safe-backend 프로젝트 구조

> 작성일: 2026-05-13

---

## 범례 (파일 상태)

| 기호 | 의미 |
|------|------|
| ✅ | 현재 기능 동작에 사용 중 |
| ❌ | 미사용 (레거시 또는 삭제 대상) |
| ⚠️ | 부분 사용 (일부 함수만 실제 호출됨) |

---

## 🐍 Python AI 서버 (FastAPI)

```
app/
├── main.py                       ✅ 앱 진입점 — 라우터 등록, CORS, Firebase 초기화
├── ai/
│   ├── __init__.py               — 패키지 초기화 (내용 없음)
│   ├── buffer.py                 ✅ Redis 관리 — 프레임 카운터, score 캐시, 최신 프레임 저장
│   └── engine.py                 ✅ AI 추론 엔진 — MediaPipe → 피처 계산 → Decision Tree
├── core/
│   ├── config.py                 ✅ 환경변수 로드 — Firebase 경로, Redis URL, JWT 설정, Kotlin internal base URL
│   ├── deps.py                   ✅ JWT 인증 의존성 — Bearer 토큰 파싱·검증
│   ├── exceptions.py             ✅ HTTP 예외 헬퍼 — not_found, conflict, unauthorized
│   ├── firebase.py               ✅ Firebase 초기화, Firestore 클라이언트, FCM 발송
│   └── security.py               ✅ JWT 검증 (decode_token만 사용)
└── domain/
    ├── camera/
    │   ├── router.py             ✅ /api/camera/* 4개 엔드포인트 등록
    │   ├── schemas.py            ✅ 응답 스키마 — StreamResponse, ScoreResponse 등
    │   └── service.py            ✅ 핵심 비즈니스 로직 — AI 추론, Kotlin internal API 호출 (realtime · fall-log 위임)
    └── devices/
        ├── router.py             ✅ /api/devices/* 2개 엔드포인트 등록
        ├── schemas.py            ✅ DeviceResponse, DeviceRegisterRequest
        └── service.py            ✅ 기기 목록 조회, 기기 등록 (Firestore)

pkl/
├── decision_tree_model.pkl       ✅ 낙상 감지 학습된 Decision Tree 모델
└── scaler.pkl                    ✅ StandardScaler — 피처 정규화에 사용

Dockerfile.python                 ✅ Python AI 서버 Docker 이미지 빌드 설정
requirements.txt                  ✅ Python 의존성 목록
```

---

## 🟡 Kotlin Spring 서버 (WebFlux)

```
src/main/kotlin/com/onsafe/backend/
├── OnSafeApplication.kt          ✅ Spring Boot 진입점
├── config/
│   ├── FirebaseConfig.kt         ✅ Firebase Admin SDK 초기화
│   ├── RedisConfig.kt            ✅ ByteArray Redis 템플릿, pub/sub 리스너 컨테이너 등록
│   ├── SecurityConfig.kt         ✅ Spring Security — JWT 필터 등록, 공개/보호 경로 분리
│   ├── SwaggerConfig.kt          ✅ SpringDoc OpenAPI — /swagger-ui.html 설정
│   └── WebSocketConfig.kt        ✅ /ws/camera/** → CameraStreamWebSocketHandler 라우팅
├── common/
│   ├── exception/
│   │   ├── BusinessException.kt      ✅ 비즈니스 예외 클래스 (ErrorCode 래핑)
│   │   ├── ErrorCode.kt              ✅ 에러코드 enum — USER_NOT_FOUND, INVALID_PASSWORD 등
│   │   └── GlobalExceptionHandler.kt ✅ 전역 예외 처리 → ApiResponse.fail() 반환
│   ├── response/
│   │   └── ApiResponse.kt            ✅ 공통 응답 래퍼 { success, message, data }
│   ├── security/
│   │   ├── JwtAuthenticationFilter.kt ✅ 요청마다 JWT 검증, 블랙리스트 확인, WS userId 검증
│   │   └── JwtProvider.kt             ✅ JWT 생성·검증·파싱 (userId, email, 만료시간)
│   └── util/
│       └── FirestoreExt.kt            ✅ Firestore 코루틴 확장함수 (awaitSingle 등)
└── domain/
    ├── auth/
    │   ├── controller/
    │   │   └── AuthController.kt      ✅ /api/auth/* 11개 엔드포인트
    │   ├── model/dto/
    │   │   ├── CheckIdRequest.kt          ✅ 아이디 중복확인 요청
    │   │   ├── FcmTokenRequest.kt         ✅ FCM 토큰 등록 요청
    │   │   ├── FindIdRequest.kt           ✅ 아이디 찾기 요청
    │   │   ├── FindIdResponse.kt          ✅ 아이디 찾기 응답 (마스킹 userId)
    │   │   ├── LoginRequest.kt            ✅ 로그인 요청 (userId, password, deviceId)
    │   │   ├── LoginResponse.kt           ✅ 로그인 응답 (JWT 토큰 포함)
    │   │   ├── RegisterRequest.kt         ✅ 회원가입 요청 (address 포함)
    │   │   ├── ResetPasswordRequest.kt    ✅ 비밀번호 변경 요청
    │   │   ├── SendEmailCodeRequest.kt    ✅ 회원가입 이메일 인증코드 발송 요청
    │   │   ├── SendResetCodeRequest.kt    ✅ 비밀번호 재설정 코드 발송 요청
    │   │   ├── TokenResponse.kt           ✅ 토큰 재발급 응답
    │   │   ├── VerifyEmailCodeRequest.kt  ✅ 이메일 인증코드 확인 요청
    │   │   └── VerifyResetCodeRequest.kt  ✅ 비밀번호 재설정 코드 확인 요청
    │   └── service/
    │       ├── AuthService.kt     ✅ 로그인·회원가입·이메일인증·비밀번호재설정 로직
    │       └── EmailService.kt    ✅ SMTP 이메일 발송 (인증코드, 재설정코드)
    ├── camera/
    │   ├── controller/
    │   │   ├── CameraController.kt        ✅ PUT /api/camera/url, 위험점수·상태·colorCode 조회
    │   │   └── CameraSessionController.kt ✅ PUT/GET /api/camera/session/* (start/stop/status)
    │   ├── model/
    │   │   ├── dto/
    │   │   │   ├── CameraSessionResponse.kt  ✅ 세션 상태 응답 (userId, status, 경과시간)
    │   │   │   ├── CameraUrlRequest.kt       ✅ 카메라 URL 업데이트 요청
    │   │   │   ├── RiskScoreResponse.kt      ✅ 위험 점수 + colorCode 응답
    │   │   │   └── RiskStatusResponse.kt     ✅ 기기 상태 응답
    │   │   └── entity/
    │   │       ├── CameraSessionStatus.kt    ✅ STANDBY / CONNECTING / LIVE enum
    │   │       ├── RealtimeData.kt           ✅ realtime_data Firestore 엔티티
    │   │       └── RiskLevel.kt              ✅ 위험 레벨 enum — colorCode 매핑
    │   ├── repository/
    │   │   ├── DeviceRepository.kt           ✅ Firestore devices — cameraUrl, userId 조회
    │   │   └── RealtimeDataRepository.kt     ✅ Firestore realtime_data 저장·조회
    │   ├── service/
    │   │   ├── CameraService.kt              ✅ 위험점수·상태 조회, colorCode 변환, URL 업데이트
    │   │   └── CameraSessionService.kt       ✅ Redis 세션 상태 관리 (start/stop/markLive)
    │   └── websocket/
    │       └── CameraStreamWebSocketHandler.kt ✅ Redis sub → WebSocket 바이너리 전달, LIVE 전환
    ├── internal/
    │   ├── controller/
    │   │   └── InternalController.kt    ✅ /internal/frame, /internal/fall-log, /internal/realtime
    │   ├── model/dto/
    │   │   ├── SaveFallLogRequest.kt    ✅ Python이 POST /internal/fall-log 호출 시 수신
    │   │   └── UpdateRealtimeRequest.kt ✅ Python이 POST /internal/realtime 호출 시 수신
    │   └── service/
    │       └── InternalService.kt       ✅ publishFrame · saveFallLog · updateRealtime 모두 사용 중
    ├── logs/
    │   ├── controller/
    │   │   └── FallLogController.kt     ✅ /api/fall-logs/* 목록·단건·확인(PATCH)·삭제
    │   ├── model/
    │   │   ├── dto/
    │   │   │   └── FallLogResponse.kt   ✅ 낙상 로그 응답 DTO
    │   │   └── entity/
    │   │       └── FallLog.kt           ✅ Firestore fall_logs 엔티티
    │   ├── repository/
    │   │   └── FallLogRepository.kt     ✅ Firestore fall_logs CRUD + 소유권 검증
    │   └── service/
    │       └── FallLogService.kt        ✅ 낙상 로그 조회·확인·삭제 비즈니스 로직
    ├── notification/
    │   ├── controller/
    │   │   └── NotificationController.kt ✅ POST /api/notification/send
    │   ├── model/dto/
    │   │   ├── NotificationRequest.kt   ✅ FCM 알림 전송 요청
    │   │   └── NotificationResponse.kt  ✅ FCM 전송 결과 응답 (messageId 포함)
    │   └── service/
    │       └── NotificationService.kt   ✅ Firebase FCM 메시지 발송
    ├── settings/
    │   ├── controller/
    │   │   └── SettingsController.kt    ✅ /api/settings/* 알림설정·보관기간 GET/PUT
    │   ├── model/
    │   │   ├── dto/
    │   │   │   ├── NotificationSettingsRequest.kt ✅ 알림 설정 변경 요청
    │   │   │   ├── RetentionSettingsRequest.kt    ✅ 보관 기간 변경 요청
    │   │   │   └── SettingsResponse.kt            ✅ 설정 조회 응답
    │   │   └── entity/
    │   │       └── UserSettings.kt      ✅ Firestore settings 엔티티
    │   ├── repository/
    │   │   └── SettingsRepository.kt    ✅ Firestore settings CRUD
    │   └── service/
    │       └── SettingsService.kt       ✅ 알림설정·보관기간 조회·변경 로직
    └── user/
        ├── controller/
        │   └── UserController.kt        ✅ GET/PUT/DELETE /api/user/{userId}
        ├── model/
        │   ├── dto/
        │   │   ├── UserResponse.kt      ✅ 유저 정보 응답
        │   │   └── UserUpdateRequest.kt ✅ 유저 정보 수정 요청
        │   └── entity/
        │       └── User.kt              ✅ Firestore users 엔티티
        ├── repository/
        │   └── UserRepository.kt        ✅ Firestore users CRUD (userId, mail, phone 조회)
        └── service/
            └── UserService.kt           ✅ 유저 조회·수정·탈퇴 비즈니스 로직

src/main/resources/
├── application.yml               ✅ Kotlin 서버 설정 (Redis, Firebase, JWT, SMTP, Swagger)
└── application-docker.yml        ✅ Docker 환경 설정 (Redis host 등 override)

Dockerfile.kotlin                 ✅ Kotlin 서버 Gradle 빌드 + 이미지 생성
build.gradle.kts                  ✅ Kotlin 의존성 및 빌드 설정
settings.gradle.kts               ✅ Gradle 프로젝트명 설정
```

---

## 🔧 공통 설정

```
docker-compose.yml       ✅ Python + Kotlin + Redis + mediamtx(RTSP) 컨테이너 구성
serviceAccountKey.json   ✅ Firebase 서비스 계정 키 (git 제외 대상)
.env                     ✅ 환경변수 파일 (git 제외 대상)
requirements.txt         ✅ Python 패키지 목록
```

---

## 📄 문서

```
v2.0_onsafe_api_spec.md                   구버전 API 명세서
v3.0_onsafe_api_spec.md                   현행 API 명세서
CHANGELOG.md                              브랜치별 변경 이력
docs/
├── camera-streaming-implementation.md    실시간 스트리밍 구현 상세
└── project-structure.md                  이 파일 — 전체 프로젝트 구조
```

---

## ⚠️ 주의사항 / 알려진 이슈

### 1. `domain/fall` 제거됨
- `CHANGELOG e2e4984`에서 삭제됨
- `domain/logs` (`FallLog`)로 대체 완료

### 2. Python ↔ Kotlin 내부 API 연동 완료 (2026-05-13)
- `camera/service.py`가 추론 후 `POST /internal/realtime` 호출 → Kotlin이 `realtime_data/{userId}` 저장
- 낙상 감지 시 `POST /internal/fall-log` 호출 → Kotlin이 `fall_logs` 저장 + FCM 발송
- Python의 직접 Firestore `fall_logs` 저장 및 FCM 발송 코드 제거 완료

### 3. `RiskLevel.kt` 임계값 수정 (2026-05-13)
- `DANGER_THRESHOLD = 75f`, `WARNING_THRESHOLD = 50f` (0~100 스케일)
- Python `_score_level()`도 동일 기준으로 통일: `≥76` 위험, `≥51` 주의, 그 외 정상
- 낙상 로그 저장 트리거: `score >= 76 OR fall == True`

### 4. `app/core/security.py`
- `decode_token`만 사용 (`deps.py`에서 호출)
- 나머지 함수 삭제 완료 (2026-05-13)

### 5. Android Studio — "Unresolved reference 'OnSafeApplication'"
- 코드 버그 아님 — Gradle sync 미완료로 인한 IDE 표시 오류
- 해결: **File → Sync Project with Gradle Files**
- 권장: IntelliJ IDEA 사용

---

## 📋 API 테스트 결과 (2026-05-13)

**테스트 환경:** Docker Compose (Kotlin `:8080`, Python `:8000`)  
**테스트 방법:** `curl` 직접 호출 + `adb reverse` 실기기 앱 테스트

### 범례

| 기호 | 의미 |
|------|------|
| ✅ | 정상 동작 확인 |
| ⚠️ | 부분 동작 (주의사항 있음) |
| ⬜ | 미테스트 (하드웨어 필요 또는 파괴적 작업) |

---

### 🟡 Kotlin Spring 서버 (포트 8080)

#### Auth

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `POST /api/auth/register` | |
| ✅ | `POST /api/auth/login` | JWT access/refresh token 발급 정상 |
| ✅ | `POST /api/auth/logout` | |
| ✅ | `POST /api/auth/check-id` | 중복/사용가능 양쪽 확인 |
| ✅ | `POST /api/auth/find-id` | userId 마스킹 처리 확인 (`api******` 형태) |
| ✅ | `POST /api/auth/fcm-token` | |
| ✅ | `POST /api/auth/refresh` | 새 토큰 쌍 발급 정상 |
| ✅ | `POST /api/auth/send-reset-code` | 이메일 발송 성공 (SMTP 환경변수 필요) |
| ✅ | `POST /api/auth/send-email-code` | 인증코드 발송 정상 |
| ✅ | `POST /api/auth/verify-email-code` | 코드 일치 시 200, 불일치 시 400 |
| ✅ | `POST /api/auth/verify-reset-code` | 코드 일치 시 200, Redis 키 삭제 확인 |
| ✅ | `POST /api/auth/reset-password` | 비밀번호 변경 후 재로그인 성공 확인 |

#### User

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/user/{userId}` | |
| ✅ | `PUT /api/user/{userId}` | name·phone 수정 후 응답에 반영 확인 |
| ✅ | `DELETE /api/user/{userId}` | 삭제 후 로그인 시도 시 404 반환 확인 |

#### Camera — Kotlin

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/camera/score/{userId}` | `realtime_data/{userId}` 고정 문서 조회 정상 |
| ✅ | `GET /api/camera/status/{userId}` | colorCode `#FF0000` (위험) 반환 확인 |
| ✅ | `PUT /api/camera/url/{deviceId}` | URL 업데이트 200 / 소유자 불일치 403 / 요청 필드명 `camera_url` (snake_case) |
| ✅ | `PUT /api/camera/session/{deviceId}/start` | CONNECTING 상태 전환 확인 |
| ✅ | `PUT /api/camera/session/{deviceId}/stop` | 촬영 종료 정상 |
| ✅ | `GET /api/camera/session/{userId}/status` | STANDBY/CONNECTING 반환 확인 |
| ⬜ | `GET /api/camera/stream/{userId}` | RTSP 카메라 연결 필요 |

#### Fall Logs

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/fall-logs/{userId}` | 목록 조회 정상 |
| ✅ | `GET /api/fall-logs/{userId}/{logId}` | 단건 조회 정상 |
| ✅ | `PATCH /api/fall-logs/{userId}/{logId}/confirm` | `is_confirmed` true 전환 확인 |
| ✅ | `DELETE /api/fall-logs/{userId}/{logId}` | 삭제 정상 |

#### Settings

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/settings/notifications/{userId}` | |
| ✅ | `PUT /api/settings/notifications/{userId}` | 변경값 응답에 반영 확인 |
| ✅ | `GET /api/settings/retention/{userId}` | |
| ✅ | `PUT /api/settings/retention/{userId}` | |

#### Notification

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `POST /api/notification/send` | FCM messageId 반환 확인 |

#### Internal (Python → Kotlin 전용)

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `POST /internal/realtime` | `realtime_data/{userId}` 저장 → `camera/score` 정상화 확인 |
| ✅ | `POST /internal/fall-log` | `fall_logs` 저장 + FCM 발송 확인 |
| ✅ | `POST /internal/frame/{userId}` | Redis pub/sub 발행 정상 |

---

### 🐍 Python AI 서버 (포트 8000)

#### Devices

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `POST /api/devices/{userId}` | 신규: 201 created / 타 소유자 기기 재귀속: 200 updated / 동일 소유자 재등록: 409 |
| ✅ | `GET /api/devices/{userId}` | 기기 목록 정상 반환 — `.stream()` 교체 + upsert 로직 수정 (2026-05-13) |

#### Camera — Python

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/camera/score/{userId}` | Redis score 캐시 기반 조회 정상 |
| ✅ | `GET /api/camera/url/{device_id}` | Firestore `camera_url` 반환 정상 |
| ✅ | `GET /api/camera/status/{device_id}` | 기기 상태 (`inactive`/`active`) 반환 정상 |
| ✅ | `POST /api/camera/stream` | Android 카메라 실측 — 추론·score Redis 저장·`/internal/realtime` 정상 확인 |

---

## 🐛 테스트 중 발견 및 수정된 이슈

### 1. 낙상 로그 API 경로 오류 — 수정 완료 (2026-05-13)

| | 경로 |
|-|------|
| 명세서 (오류) | `/api/logs/{userId}` |
| 실제 (정상) | `/api/fall-logs/{userId}` |

### 2. confirm 경로 오류 — 수정 완료 (2026-05-13)

| | 경로 |
|-|------|
| 명세서 (오류) | `PATCH /api/camera/fall-logs/{log_id}/confirm` |
| 실제 (정상) | `PATCH /api/fall-logs/{userId}/{logId}/confirm` |

### 3. `GET /api/devices/{userId}` 빈 목록 — 수정 완료 (2026-05-13)

- **원인 1:** `.where().get()` → `.where().stream()` 교체 (async 쿼리 결과 누락 방지)
- **원인 2:** 동일 `device_id`가 다른 `user_id`로 기등록된 경우 소유자 불일치
- **수정:** `register_device` upsert 로직 추가 — 타 소유자 기기 재귀속 시 `user_id` 업데이트 (200 `updated`)
- **검증:** 신규 등록 → GET 정상 / 기기 재귀속 → 새 소유자 GET 정상 / 이전 소유자 GET 빈 목록 정상

### 4. 내부 API JSON 필드명 오류 — 수정 완료 (2026-05-13)

Kotlin 전역 `SNAKE_CASE` 정책으로 인해 Python이 camelCase로 전송 시 역직렬화 실패.

| 엔드포인트 | 수정 전 | 수정 후 |
|-----------|---------|---------|
| `POST /internal/realtime` | `userId` | `user_id` |
| `POST /internal/fall-log` | `logId`, `deviceId`, `userId`, `isConfirmed` | `log_id`, `device_id`, `user_id`, `is_confirmed` |
| `PUT /api/camera/url` | `cameraUrl` | `camera_url` |

### 5. `realtime_data` Firestore 복합 인덱스 누락 — 수정 완료 (2026-05-13)

- **원인:** 사용자별 최신 2,000개 유지 쿼리 (`where user_id + order_by timestamp + offset`) 실행 시 복합 인덱스 필요
- **수정:** Firebase 콘솔에서 인덱스 생성 (`user_id ASC`, `timestamp DESC`) + 코드에 `try/except` 방어 처리 추가
