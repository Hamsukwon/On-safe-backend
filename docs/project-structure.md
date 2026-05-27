
# On-safe-backend 프로젝트 구조

> 최종 수정일: 2026-05-27 (feature/ses-email-migration)

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
│   ├── __init__.py               — 패키지 초기화
│   ├── buffer.py                 ✅ Redis 관리 — 프레임 카운터, score 캐시, 최신 프레임,
│   │                                주의(51~75) 이벤트 5분 쿨다운 (check_caution_cooldown)
│   └── engine.py                 ✅ AI 추론 엔진 — MediaPipe → 피처 계산 → Decision Tree
├── core/
│   ├── config.py                 ✅ 환경변수 로드 — Firebase 경로, Redis URL, JWT 설정,
│   │                                Kotlin internal base URL, firebase_storage_bucket
│   ├── deps.py                   ✅ JWT 인증 의존성 — Bearer 토큰 파싱·검증
│   ├── exceptions.py             ✅ HTTP 예외 헬퍼 — not_found, conflict, unauthorized
│   ├── firebase.py               ✅ Firebase 초기화 (storageBucket 옵션 조건부 주입),
│   │                                Firestore 클라이언트, FCM 발송
│   ├── security.py               ✅ JWT 검증 (decode_token만 사용)
│   └── storage.py                ✅ Firebase Storage 업로드 추상화 — upload_thumbnail()
│                                    GCS 경로 반환, AWS S3 마이그레이션 시 이 모듈만 교체
└── domain/
    ├── camera/
    │   ├── router.py             ✅ /api/camera/* 4개 엔드포인트 등록
    │   ├── schemas.py            ✅ 응답 스키마 — StreamResponse, ScoreResponse 등
    │   └── service.py            ✅ 핵심 비즈니스 로직
    │                                - score≥76 or fall → _save_fall_log (썸네일 업로드 포함)
    │                                - score 51~75 → 5분 쿨다운 통과 시 _save_fall_log
    │                                - Kotlin internal API 호출 (realtime · fall-log 위임)
    └── devices/
        ├── router.py             ✅ /api/devices/* 2개 엔드포인트 등록 (GET 목록 + POST 등록)
        ├── schemas.py            ✅ DeviceResponse, DeviceRegisterRequest
        └── service.py            ✅ 기기 목록 조회(get_devices), 기기 등록 (Firestore)

pkl/
├── decision_tree_model.pkl       ✅ 낙상 감지 학습된 Decision Tree 모델
└── scaler.pkl                    ✅ StandardScaler — 피처 정규화에 사용

scripts/
├── setup_storage.py              ✅ GCS Lifecycle(30일 자동삭제) + CORS 설정 (gsutil 불필요)
├── test_storage_api.py           ✅ Firebase Storage API 통합 테스트
│                                    (thumbnail · download · signed URL 흐름)
└── test_caution_notification.py  ✅ 주의 알림 및 쿨다운 동작 통합 테스트

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
│   ├── SesConfig.kt              ✅ AWS SES SesAsyncClient Bean 등록 (aws.ses.region 환경변수)
│   ├── SwaggerConfig.kt          ✅ SpringDoc OpenAPI — /swagger-ui.html 설정
│   └── WebSocketConfig.kt        ✅ /ws/camera/** → CameraStreamWebSocketHandler 라우팅
├── common/
│   ├── exception/
│   │   ├── BusinessException.kt      ✅ 비즈니스 예외 클래스 (ErrorCode 래핑)
│   │   ├── ErrorCode.kt              ✅ 에러코드 enum — USER_NOT_FOUND, THUMBNAIL_NOT_FOUND,
│   │   │                                FCM_SEND_FAILED, EXPIRED_TOKEN, INVALID_TOKEN 등
│   │   └── GlobalExceptionHandler.kt ✅ 전역 예외 처리 → ApiResponse.fail() 반환
│   │                                    BusinessException / WebExchangeBindException /
│   │                                    ServerWebInputException / MethodNotAllowedException(405) /
│   │                                    Exception(500) 핸들러 등록
│   ├── response/
│   │   └── ApiResponse.kt            ✅ 공통 응답 래퍼 { success, message, data }
│   ├── security/
│   │   ├── JwtAuthenticationFilter.kt ✅ 요청마다 JWT 검증, 블랙리스트 확인, WS userId 검증
│   │   │                                 만료/무효/블랙리스트 토큰 → JSON 401 즉시 반환
│   │   │                                 (기존: 필터 통과 → Spring Security 403)
│   │   └── JwtProvider.kt             ✅ JWT 생성·검증·파싱 (userId, email, 만료시간)
│   │                                     getValidationError(): EXPIRED_TOKEN / INVALID_TOKEN 구분
│   ├── storage/
│   │   └── StorageService.kt          ✅ GCS V4 Signed URL 발급 (ServiceAccountCredentials,
│   │                                     기본 1시간 유효, generateSignedUrl())
│   └── util/
│       └── FirestoreExt.kt            ✅ Firestore 코루틴 확장함수 (awaitSingle 등)
└── domain/
    ├── auth/
    │   ├── controller/
    │   │   └── AuthController.kt      ✅ /api/auth/* 엔드포인트
    │   ├── model/dto/
    │   │   ├── CheckIdRequest.kt          ✅ 아이디 중복확인 요청
    │   │   ├── FcmTokenUpdateRequest.kt   ✅ FCM 토큰 갱신 요청 (FcmTokenRequest 대체)
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
    │       └── EmailService.kt    ✅ AWS SES SDK 이메일 발송 (인증코드, 재설정코드)
│                                 SdkClientException(네트워크/타임아웃) + SesException(서비스 오류)
│                                 양쪽 모두 MAIL_SEND_FAILED 변환, 로그 레벨 구분(error/warn)
    ├── camera/
    │   ├── controller/
    │   │   ├── CameraController.kt        ✅ PUT /api/camera/url, 위험점수·상태·colorCode 조회
    │   │   ├── CameraSessionController.kt ✅ PUT/GET /api/camera/session/* (start/stop/status)
    │   │   └── DeviceController.kt        ✅ GET /api/devices/{userId} — 사용자 기기 목록 조회
    │   ├── model/
    │   │   ├── dto/
    │   │   │   ├── CameraSessionResponse.kt  ✅ 세션 상태 응답 (userId, status, 경과시간)
    │   │   │   ├── CameraUrlRequest.kt       ✅ 카메라 URL 업데이트 요청
    │   │   │   ├── DeviceResponse.kt         ✅ 기기 목록 단건 응답 DTO
    │   │   │   ├── RiskScoreResponse.kt      ✅ 위험 점수 + colorCode 응답
    │   │   │   └── RiskStatusResponse.kt     ✅ 기기 상태 응답
    │   │   └── entity/
    │   │       ├── CameraSessionStatus.kt    ✅ STANDBY / CONNECTING / LIVE enum
    │   │       ├── RealtimeData.kt           ✅ realtime_data Firestore 엔티티
    │   │       └── RiskLevel.kt              ✅ 위험 레벨 enum — colorCode 매핑
    │   ├── repository/
    │   │   ├── DeviceRepository.kt           ✅ Firestore devices — cameraUrl, userId, 목록 조회
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
    │   │   ├── SaveFallLogRequest.kt    ✅ Python → POST /internal/fall-log 수신 (imageUrl 포함)
    │   │   └── UpdateRealtimeRequest.kt ✅ Python → POST /internal/realtime 수신
    │   └── service/
    │       └── InternalService.kt       ✅ publishFrame · saveFallLog · updateRealtime
    │                                       - fall/score≥76: 위험·낙상 FCM 알림
    │                                       - score 51~75: 주의 FCM 알림 (Python 쿨다운으로 빈도 제한)
    │                                       - sendNotificationSafe(): FCM 실패 흡수(runCatching)
    │                                         DB 저장·FCM 전송 독립 동작, 재시도 안전성 보장
    ├── logs/
    │   ├── controller/
    │   │   └── FallLogController.kt     ✅ /api/fall-logs/*
    │   │                                   - 목록(?level 필터)·단건·확인(PATCH)·삭제
    │   │                                   - GET /{userId}/{logId}/thumbnail → signed URL JSON
    │   │                                   - GET /{userId}/{logId}/download  → 302 redirect
    │   ├── model/
    │   │   ├── dto/
    │   │   │   └── FallLogResponse.kt   ✅ 낙상 로그 응답 DTO (hasThumbnail: Boolean 포함)
    │   │   └── entity/
    │   │       └── FallLog.kt           ✅ Firestore fall_logs 엔티티 (imageUrl: String? 포함)
    │   ├── repository/
    │   │   └── FallLogRepository.kt     ✅ Firestore fall_logs CRUD + 소유권 검증 + image_url 매핑
    │   └── service/
    │       └── FallLogService.kt        ✅ 낙상 로그 조회·확인·삭제, getSignedUrl() (StorageService 위임)
    ├── notification/
    │   ├── controller/
    │   │   └── NotificationController.kt ✅ POST /api/notification/send
    │   ├── model/dto/
    │   │   ├── NotificationRequest.kt   ✅ FCM 알림 전송 요청
    │   │   └── NotificationResponse.kt  ✅ FCM 전송 결과 응답 (messageId 포함)
    │   └── service/
    │       └── NotificationService.kt   ✅ Firebase FCM 메시지 발송
│                                       FCM 실패 시 BusinessException(FCM_SEND_FAILED) throw
│                                       FCM 토큰 없으면 예외 없이 ok 반환 (정상 케이스)
    ├── settings/
    │   ├── controller/
    │   │   └── SettingsController.kt    ✅ GET/PUT /notifications/{userId}, GET /retention/{userId}
    │   │                                   (retention 보관기간 고정 30일 — PUT 없음)
    │   ├── model/
    │   │   ├── dto/
    │   │   │   ├── NotificationSettingsRequest.kt ✅ 알림 설정 변경 요청 (notification/sound/vibration)
    │   │   │   └── SettingsResponse.kt            ✅ 알림 설정 응답 / 보관기간 응답 (고정 30일)
    │   │   └── entity/
    │   │       └── UserSettings.kt      ✅ Firestore settings 엔티티 (notification/sound/vibration)
    │   ├── repository/
    │   │   └── SettingsRepository.kt    ✅ Firestore settings CRUD (fall_sensitivity·retention_days 제거)
    │   └── service/
    │       └── SettingsService.kt       ✅ 알림설정 조회·변경 / 보관기간 상수 30 반환
    └── user/
        ├── controller/
        │   └── UserController.kt        ✅ GET/PUT/DELETE /api/users/{userId}
        │                                   PUT /api/users/{userId}/fcm-token
        ├── model/
        │   ├── dto/
        │   │   ├── UserResponse.kt      ✅ 유저 정보 응답
        │   │   └── UserUpdateRequest.kt ✅ 유저 정보 수정 요청
        │   └── entity/
        │       └── User.kt              ✅ Firestore users 엔티티
        ├── repository/
        │   └── UserRepository.kt        ✅ Firestore users CRUD
        └── service/
            └── UserService.kt           ✅ 유저 조회·수정·탈퇴 비즈니스 로직

src/test/kotlin/com/onsafe/backend/
├── domain/
│   ├── auth/
│   │   ├── AuthServiceTest.kt        ✅ 로그인·회원가입·아이디찾기·코드검증·토큰갱신 예외 경로 (16개)
│   │   └── EmailServiceTest.kt       ✅ SdkClientException·SesException → MAIL_SEND_FAILED (4개)
│   ├── camera/
│   │   └── CameraServiceTest.kt      ✅ REALTIME_DATA_NOT_FOUND·CAMERA_NOT_FOUND·FORBIDDEN 등 (5개)
│   ├── internal/
│   │   └── InternalServiceTest.kt    ✅ FCM 실패 시 DB 저장 보장·점수별 알림 조건 (6개)
│   ├── logs/
│   │   └── FallLogServiceTest.kt     ✅ LOG_NOT_FOUND·THUMBNAIL_NOT_FOUND (5개)
│   ├── notification/
│   │   └── NotificationServiceTest.kt ✅ USER_NOT_FOUND·FCM 토큰 없음·FCM 성공·FCM_SEND_FAILED (4개)
│   ├── settings/
│   │   └── SettingsServiceTest.kt    ✅ 알림 설정 조회·변경·기본값 생성 (6개, 기존)
│   └── user/
│       └── UserServiceTest.kt        ✅ 주소·비밀번호 수정·프로필 조회 (5개, 기존)

src/main/resources/
├── application.yml               ✅ Kotlin 서버 설정 (Redis, Firebase, JWT, SMTP, Swagger,
│                                    firebase.storage.bucket)
└── application-docker.yml        ✅ Docker 환경 설정 (Redis host 등 override,
                                     firebase.storage.bucket)

Dockerfile.kotlin                 ✅ Kotlin 서버 Gradle 빌드 + 이미지 생성
build.gradle.kts                  ✅ Kotlin 의존성 및 빌드 설정
settings.gradle.kts               ✅ Gradle 프로젝트명 설정
```

---

## 🔧 공통 설정

```
docker-compose.yml       ✅ Python + Kotlin + Redis 컨테이너 구성
                            FIREBASE_STORAGE_BUCKET env 주입 (썸네일 signed URL용)
                            mediamtx(RTSP 테스트 서버) 제거됨
serviceAccountKey.json   ✅ Firebase 서비스 계정 키 (git 제외 대상)
.env                     ✅ 환경변수 파일 (git 제외 대상, FIREBASE_STORAGE_BUCKET 포함)
.env.example             ✅ 환경변수 예시 (FIREBASE_STORAGE_BUCKET 포함)
gcs-lifecycle.json       ✅ GCS fall-thumbnails/ 30일 자동삭제 Lifecycle 정책
storage.rules            ✅ Firebase Storage 보안 규칙 (서비스 계정 전용)
requirements.txt         ✅ Python 패키지 목록
```

---

## 📄 문서

```
README.md                                   프로젝트 개요
CHANGELOG.md                                브랜치별 변경 이력 (feature/parent-main 포함)
v2.0_onsafe_api_spec.md                     v2.0 API 명세서
v3.0_onsafe_api_spec.md                     v3.0 API 명세서
docs/
├── camera-streaming-implementation.md      실시간 스트리밍 구현 상세
├── project-structure.md                    이 파일 — 전체 프로젝트 구조
└── unimplemented-items.md                  미구현 항목 목록 (#1 age/relation, Option2 MP4)
                                            MP4 AWS S3 비용 시나리오 및 병목 분석 포함
```

---

## ⚠️ 주의사항 / 알려진 동작

### 1. 낙상 로그 저장 트리거 (2026-05-17 기준)
- **score ≥ 76 or fall = True** → 즉시 FallLog 저장 + 위험/낙상 FCM 알림 (쿨다운 없음)
- **score 51~75 (주의)** → Python `check_caution_cooldown()` 통과 시만 FallLog 저장 + 주의 FCM 알림
  - 쿨다운: 기본 5분 (Redis `caution_cd:{userId}` NX 플래그)
  - `/internal/fall-log` 직접 호출 시 쿨다운 우회됨 (Python 서버 측 로직)
- **score < 51 (정상)** → FallLog 미저장, 알림 없음

### 2. 썸네일 저장 방식 (GCS Signed URL)
- Python이 낙상 감지 시 JPEG → `fall-thumbnails/{logId}.jpg` GCS 경로로 Firebase Storage 업로드
- Firestore `fall_logs` 문서에 `image_url` = GCS 경로 저장 (URL 아님)
- 클라이언트는 `hasThumbnail: Boolean` 만 수신 (GCS 경로 비노출)
- `GET /thumbnail` 또는 `GET /download` 호출 시 Kotlin StorageService가 V4 Signed URL 온디맨드 발급 (1시간 유효)

### 3. Python ↔ Kotlin 내부 API 연동
- `camera/service.py`가 매 추론 후 `POST /internal/realtime` 호출 → Kotlin이 `realtime_data/{userId}` **단일 문서 덮어쓰기**
  - 저장 필드: `score(Float)`, `level(String)`, `updated_at(Timestamp)` — 문서 ID = `userId`, 사용자당 1개
  - Python의 Firestore 직접 쓰기 없음 (모든 Firestore 접근은 Kotlin을 통해서만)
- 낙상·위험·주의 감지 시 `POST /internal/fall-log` 호출 → Kotlin이 `fall_logs` 저장 + FCM 발송
- 직접 Firestore 저장 및 FCM 발송 코드 Python 측 없음

### 4. `RiskLevel.kt` 임계값 (Python과 통일)
- `DANGER`: score ≥ 76, `WARNING`: score 51~75, `NORMAL`: score ≤ 50
- Python `_score_level()` 동일 기준 적용

### 5. Kotlin Jackson SNAKE_CASE 전략
- `spring.jackson.property-naming-strategy: SNAKE_CASE` 전역 설정
- 모든 JSON 입출력은 snake_case (예: `imageUrl` → `image_url`)
- Python internal API 호출 시 snake_case 필드명 필수

### 6. `domain/fall` 제거됨
- `CHANGELOG e2e4984`에서 삭제됨
- `domain/logs` (`FallLog`)로 대체 완료

### 7. Android Studio — "Unresolved reference 'OnSafeApplication'"
- 코드 버그 아님 — Gradle sync 미완료로 인한 IDE 표시 오류
- 해결: **File → Sync Project with Gradle Files**

### 8. JWT 토큰 오류 응답 (2026-05-27 변경)
- 만료 토큰: `401 {"success":false,"message":"만료된 토큰입니다.","data":null}`
- 무효/위조 토큰: `401 {"success":false,"message":"유효하지 않은 토큰입니다.","data":null}`
- 블랙리스트(로그아웃) 토큰: 동일하게 `401 INVALID_TOKEN` JSON 반환
- 공개 경로(`/api/auth/**`)에도 잘못된 토큰 포함 시 즉시 401 반환 (정상 설계 — 공개 경로에 토큰 불필요)

---

## 📋 API 테스트 결과 (2026-05-17)

**테스트 환경:** Docker Compose (Kotlin `:8080`, Python `:8000`)

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
| ✅ | `POST /api/auth/find-id` | userId 마스킹 처리 확인 |
| ✅ | `POST /api/auth/refresh` | 새 토큰 쌍 발급 정상 |
| ✅ | `POST /api/auth/send-reset-code` | 이메일 발송 성공 (SMTP 환경변수 필요) |
| ✅ | `POST /api/auth/send-email-code` | |
| ✅ | `POST /api/auth/verify-email-code` | |
| ✅ | `POST /api/auth/verify-reset-code` | |
| ✅ | `POST /api/auth/reset-password` | |

#### User

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/users/{userId}` | |
| ✅ | `PUT /api/users/{userId}` | |
| ✅ | `DELETE /api/users/{userId}` | |
| ✅ | `PUT /api/users/{userId}/fcm-token` | FcmTokenUpdateRequest 사용 |

#### Camera — Kotlin

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/camera/score/{userId}` | |
| ✅ | `GET /api/camera/status/{userId}` | |
| ✅ | `PUT /api/camera/url/{deviceId}` | |
| ✅ | `PUT /api/camera/session/{deviceId}/start` | |
| ✅ | `PUT /api/camera/session/{deviceId}/stop` | |
| ✅ | `GET /api/camera/session/{userId}/status` | |
| ⬜ | `GET /api/camera/stream/{userId}` | RTSP 카메라 연결 필요 |

#### Devices — Kotlin

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/devices/{userId}` | 사용자 기기 목록 조회 |

#### Fall Logs

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/fall-logs/{userId}` | 전체 목록 |
| ✅ | `GET /api/fall-logs/{userId}/counts` | 탭별 건수 조회 |
| ✅ | `GET /api/fall-logs/{userId}?level=위험` | 레벨 필터 |
| ✅ | `GET /api/fall-logs/{userId}?level=주의` | 레벨 필터 |
| ✅ | `GET /api/fall-logs/{userId}/{logId}` | 단건 조회 |
| ✅ | `GET /api/fall-logs/{userId}/{logId}/thumbnail` | signed URL JSON 반환 |
| ✅ | `GET /api/fall-logs/{userId}/{logId}/download` | 302 redirect to signed URL |
| ✅ | `PATCH /api/fall-logs/{userId}/{logId}/confirm` | |
| ✅ | `DELETE /api/fall-logs/{userId}/{logId}` | |

#### Settings

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/settings/notifications/{userId}` | notificationEnabled, soundEnabled, vibrationEnabled |
| ✅ | `PUT /api/settings/notifications/{userId}` | fallSensitivity 제거됨 |
| ✅ | `GET /api/settings/retention/{userId}` | 항상 retentionDays: 30 반환 |
| — | `PUT /api/settings/retention/{userId}` | 제거됨 (서버 고정 30일) |

#### Notification

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `POST /api/notification/send` | FCM messageId 반환 확인 |

#### Internal (Python → Kotlin 전용)

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `POST /internal/realtime` | |
| ✅ | `POST /internal/fall-log` | imageUrl(GCS 경로) 포함, score 기반 FCM 분기 |
| ✅ | `POST /internal/frame/{userId}` | Redis pub/sub 발행 정상 |

---

### 🐍 Python AI 서버 (포트 8000)

#### Devices

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `POST /api/devices/{userId}` | 신규 201 / 타 소유자 재귀속 200 / 동일 소유자 409 |
| ✅ | `GET /api/devices/{userId}` | 기기 목록 정상 반환 |

#### Camera — Python

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/camera/score/{userId}` | Redis score 캐시 기반 |
| ✅ | `GET /api/camera/url/{device_id}` | Firestore camera_url 반환 |
| ✅ | `GET /api/camera/status/{device_id}` | 기기 상태 반환 |
| ✅ | `POST /api/camera/stream` | 추론·score 저장·internal API 호출 확인 |

---

## 🐛 알려진 이슈 및 수정 이력

| 날짜 | 이슈 | 상태 |
|------|------|------|
| 2026-05-13 | 낙상 로그 API 경로 오류 (`/api/logs` → `/api/fall-logs`) | ✅ 수정 완료 |
| 2026-05-13 | confirm 경로 오류 | ✅ 수정 완료 |
| 2026-05-13 | `GET /api/devices/{userId}` 빈 목록 (`.get()` → `.stream()`, upsert 로직) | ✅ 수정 완료 |
| 2026-05-13 | 내부 API JSON 필드명 camelCase → snake_case | ✅ 수정 완료 |
| 2026-05-13 | `realtime_data` Firestore 복합 인덱스 누락 | ✅ 해소 — 현재 구조는 `userId` 단일 문서 덮어쓰기(복합 인덱스 불필요) |
| 2026-05-17 | 주의(51~75) 이벤트 미저장·알림 없음 | ✅ Python 쿨다운 + Kotlin 분기 구현 완료 |
| 2026-05-19 | `POST /internal/fall-log` FCM 토큰 무효 시 500 (`NotificationService` Firebase 예외 미처리) | ✅ 구조 개선 (2026-05-27) — FCM 실패 시 `BusinessException(FCM_SEND_FAILED)` throw, `InternalService.sendNotificationSafe()`에서 흡수. DB 저장·FCM 전송 독립 보장 |
| 2026-05-19 | Python `GET /api/devices/{userId}` 405 (라우터에 GET 미등록) | ✅ `router.py` GET 엔드포인트 + `service.py` `get_devices()` 추가 |
