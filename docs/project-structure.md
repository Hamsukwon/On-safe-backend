
# On-safe-backend 프로젝트 구조

> 최종 수정일: 2026-07-13 (main — PR #16~#18 및 임계값 수정(f62a153, ea13763) 반영)

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
│                                    startup: _load_models() eager load, GET /health 엔드포인트
│                                    logging.config.dictConfig 전역 설정 (PR #18, print() 대체)
│                                    CORS allow_origins: kotlin_internal_base + CORS_ORIGINS 환경변수
│                                    (기존 "*" 와일드카드 제거, PR #18 — communication-issues-analysis.md 문제3 해결)
├── ai/
│   ├── __init__.py               — 패키지 초기화
│   ├── buffer.py                 ✅ Redis 관리 — score 캐시, 주의(51~75) 이벤트 5분 쿨다운
│   │                                save_latest_frame/get_latest_frame: 보호자 릴레이 보류 중
│   └── engine.py                 ✅ AI 추론 엔진 — landmark JSON → 30프레임 윈도우 → XGBoost
│                                    infer_landmarks(landmarks, device_id, timestamp)
│                                    Step2(NaN보정) → Step3(SGV) → Step4(골반정규화)
│                                    → Step5(47피처) → Step6(StandardScaler) → predict_proba
│                                    _classify_level(score): score → 정상/주의/위험 반환 (PR #14)
├── core/
│   ├── config.py                 ✅ 환경변수 로드 — Firebase 경로, Redis URL, JWT 설정,
│   │                                Kotlin internal base URL, firebase_storage_bucket
│   ├── deps.py                   ✅ JWT 인증 의존성 — Bearer 토큰 파싱·검증 (HTTP 엔드포인트용)
│   ├── exceptions.py             ✅ HTTP 예외 헬퍼 — not_found, conflict, unauthorized
│   ├── firebase.py               ✅ Firebase 초기화 (storageBucket 옵션 조건부 주입),
│   │                                Firestore 클라이언트, FCM 발송
│   ├── security.py               ✅ JWT 검증 (decode_token — WebSocket ?token= 인증에도 사용)
│   └── storage.py                ✅ Firebase Storage 업로드 추상화 — upload_thumbnail()
│                                    GCS 경로 반환, AWS S3 마이그레이션 시 이 모듈만 교체
└── domain/
    ├── camera/
    │   ├── router.py             ✅ HTTP: /api/camera/* 3개 (score·status·url)
    │   │                            WS: /ws/stream?token= (landmark 수신·추론·결과 반환)
    │   │                            ws_router 분리하여 main.py에 별도 등록
    │   ├── schemas.py            ✅ StreamResponse(score, fall, level, log_id), ScoreResponse 등
    │   └── service.py            ✅ 핵심 비즈니스 로직 (PR #18: print → logging 전환)
    │                                - process_frame(): landmark → 추론 → realtime·fall-log 위임
    │                                - score>75 or fall → _save_fall_log (jpeg_bytes=None, 항상 None — 주의사항 2번 참고)
    │                                - score>50 (51~75 아님, 경계값 제외) → 5분 쿨다운 통과 시 _save_fall_log
    │                                - engine.py의 WARNING_THRESHOLD/CRITICAL_THRESHOLD(50/75) 상수를 import해 사용 (2026-07-13, 하드코딩 제거)
    │                                - Kotlin internal API 호출 (realtime · fall-log 위임)
    │                                - _score_level() 제거됨 (PR #14) → engine 반환 level 직접 사용
    └── devices/
        ├── router.py             ✅ /api/devices/* 2개 엔드포인트 등록 (GET 목록 + POST 등록)
        ├── schemas.py            ✅ DeviceResponse, DeviceRegisterRequest
        └── service.py            ✅ 기기 목록 조회(get_devices), 기기 등록 (Firestore)

pkl/
├── xgb_model.pkl                 ✅ 낙상 감지 학습된 XGBoost 모델 (Decision Tree에서 교체)
└── scaler.pkl                    ✅ StandardScaler — 47개 피처 정규화

scripts/
├── setup_storage.py              ✅ GCS Lifecycle(30일 자동삭제) + CORS 설정 (gsutil 불필요)
├── test_storage_api.py           ✅ Firebase Storage API 통합 테스트
│                                    (thumbnail · download · signed URL 흐름)
├── test_caution_notification.py  ✅ 주의 알림 및 쿨다운 동작 통합 테스트 (Kotlin 서버 기준)
├── test_engine.py                ✅ engine.py infer_landmarks() 단위 테스트 (서버 불필요)
└── test_ws_stream.py             ✅ WebSocket /ws/stream 통합 테스트 (JWT 토큰 필요)

Dockerfile.python                 ✅ Python AI 서버 Docker 이미지 빌드 설정
                                     apt-get 레이어 제거 (opencv/mediapipe 불필요), pip install 단순화
requirements.txt                  ✅ Python 의존성 목록 (xgboost 추가, mediapipe·opencv 제거)
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
    │   │   │   ├── RiskScoreResponse.kt      ✅ 위험 점수 응답 (score, level, updatedAt — PR #15)
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
    │   │                                   - /download 삭제됨 (PR #15, /thumbnail로 대체)
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
    │   ├── controller/               ❌ NotificationController.kt 삭제됨 (PR #15)
    │   │                                POST /api/notification/send 외부 엔드포인트 제거
    │   ├── model/dto/
    │   │   ├── NotificationRequest.kt   ✅ FCM 알림 전송 요청 (InternalService 내부 사용)
    │   │   └── NotificationResponse.kt  ✅ FCM 전송 결과 응답 (InternalService 내부 사용)
    │   └── service/
    │       └── NotificationService.kt   ✅ Firebase FCM 메시지 발송 (InternalService에서만 호출)
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
        │                                   POST /api/users/{userId}/verify-password (PR #15 신규)
        ├── model/
        │   ├── dto/
        │   │   ├── UserResponse.kt          ✅ 유저 정보 응답
        │   │   ├── UserUpdateRequest.kt     ✅ 유저 정보 수정 요청
        │   │   └── VerifyPasswordRequest.kt ✅ 비밀번호 사전 확인 요청 (PR #15 신규)
        │   └── entity/
        │       └── User.kt              ✅ Firestore users 엔티티
        ├── repository/
        │   └── UserRepository.kt        ✅ Firestore users CRUD
        └── service/
            └── UserService.kt           ✅ 유저 조회·수정·탈퇴 비즈니스 로직
                                            verifyPassword(): BCrypt 비교, 불일치 시 UNAUTHORIZED

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
                            python-ai healthcheck 추가 (GET /health, 15초 주기)
                            mediamtx(RTSP 테스트 서버) 제거됨
                            Redis AOF 영속성(appendonly yes, everysec) + maxmemory 512mb
                            + allkeys-lru 정책 + redis-data 볼륨 마운트 (PR #18)
.github/workflows/backend-ci.yml  ✅ GitHub Actions — Python import 검증·pytest,
                            Kotlin 단위테스트, Docker 이미지 빌드, main 푸시 시
                            Docker Hub push (PR #18 신규)
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
CHANGELOG.md                                브랜치별 변경 이력
v3.0_onsafe_api_spec.md                     v3.0 API 명세서 (HTTP+JPEG 기준, 구버전)
v4.0_onsafe_api_spec.md                     v4.1 API 명세서 (WebSocket+landmark 기준, 최신)
docs/
├── backend-logic-guide.md                  백엔드 내부 로직 구조 가이드 (PR #15 신규)
├── project-structure.md                    이 파일 — 전체 프로젝트 구조
├── ai-engine-implementation-record.md             AI 추론 엔진 마이그레이션 계획 (Step 1~6 완료)
├── ai-engine-migration-presentation.md     AI 엔진 마이그레이션 발표 대본
├── ai-buffer-refactor-analysis.md          buffer.py 리팩터링 — should_infer() 제거 설계 (Step 3)
├── ai-ondevice-plan.md                     On-device 추론 (Option C) 설계 계획
├── ai-migration-test-report.md             AI 마이그레이션 테스트 보고서 (2026-05-29)
├── ai-runtime-analysis.md                  런타임 리소스·병목·동시성·트랜잭션 분석
├── notification-service-architecture.md    NotificationService 분리 설계 결정
├── communication-issues-analysis.md        HTTP+WebSocket 통신 구조 문제 분석 (2026-07-08, 문제3 PR #18로 해결)
├── redis-operations-analysis.md            Redis 운영 분석 — TTL·메모리·eviction (2026-07-08, 일부 PR #18로 해결)
├── 20260602_settings_API_연동_작업정리.md   설정 화면 API 연동 작업 정리 (Android + 백엔드)
└── mp4-storage-migration.md                낙상 로그 썸네일(JPEG)→동영상(MP4) 저장 배관 전환 작업 기록 (신규)
```

---

## ⚠️ 주의사항 / 알려진 동작

### 1. 낙상 로그 저장 트리거 (2026-07-13 기준 — 임계값 50/75로 수정, 경계값 제외)
- **score > 75 or fall = True** → 즉시 FallLog 저장 + 위험/낙상 FCM 알림 (쿨다운 없음)
- **score > 50 (주의, 50 초과 75 이하)** → Python `check_caution_cooldown()` 통과 시만 FallLog 저장 + 주의 FCM 알림
  - 쿨다운: 기본 5분 (Redis `caution_cd:{userId}` NX 플래그)
  - `/internal/fall-log` 직접 호출 시 쿨다운 우회됨 (Python 서버 측 로직)
- **score ≤ 50 (정상)** → FallLog 미저장, 알림 없음
- 값 변경 이력: 최초 51/76(`>=`) → `f62a153`/`ea13763`(2026-06-05, 2026-06-24)에서 50/75(`>`)로 통일. Kotlin `RiskLevel.kt`도 동일 커밋에서 함께 수정됨
- `service.py`는 기존에 76/51을 하드코딩해 engine.py(당시 이미 50/75로 수정됨)와 값이 어긋나 있었으나, 2026-07-13에 engine.py의 `WARNING_THRESHOLD`/`CRITICAL_THRESHOLD` 상수를 import하는 방식으로 수정해 두 값이 항상 일치하도록 개선함

### 2. 썸네일(→ 동영상) 저장 방식 — 현재 사실상 비활성 상태 (2026-07-13 확인)
- Step 5(WebSocket+landmark 전환) 이후 Python 서버는 JPEG 프레임을 더 이상 받지 않음
- `process_frame()`이 `_save_fall_log()`를 호출할 때 **항상 `jpeg_bytes=None`으로 호출** → `upload_thumbnail()`은 현재 아키텍처에서 호출될 수 없는 데드 코드였고, `fall_logs.image_url`은 실질적으로 항상 null
- GCS Signed URL 발급 흐름(`GET /thumbnail`, Kotlin `StorageService` V4 서명) 자체는 정상 동작하지만 저장되는 데이터가 없어 실사용 불가 상태였음
- `feature/fall-log-mp4-storage` 브랜치에서 썸네일(JPEG) 저장 배관을 동영상(MP4) 저장 배관으로 교체 작업 진행 중 — 실제 비디오 바이트 소스는 이번 작업 범위 밖(추후 별도 설계), 배관만 우선 image→video로 전환. 상세 진행 기록은 `docs/mp4-storage-migration.md` 참고

### 3. Python ↔ Kotlin 내부 API 연동
- `camera/service.py`가 매 추론 후 `POST /internal/realtime` 호출 → Kotlin이 `realtime_data/{userId}` **단일 문서 덮어쓰기**
  - 저장 필드: `score(Float)`, `level(String)`, `updated_at(Timestamp)` — 문서 ID = `userId`, 사용자당 1개
  - Python의 Firestore 직접 쓰기 없음 (모든 Firestore 접근은 Kotlin을 통해서만)
- 낙상·위험·주의 감지 시 `POST /internal/fall-log` 호출 → Kotlin이 `fall_logs` 저장 + FCM 발송
- 직접 Firestore 저장 및 FCM 발송 코드 Python 측 없음

### 4. `RiskLevel.kt` 임계값 (Python과 통일, 2026-06-05/06-24 재수정)
- `DANGER`: score > 75 (`DANGER_THRESHOLD = 75f`), `WARNING`: score 50 초과 75 이하 (`WARNING_THRESHOLD = 50f`), `NORMAL`: score ≤ 50
- `fromScore()` 조건: `> DANGER_THRESHOLD → DANGER`, `> WARNING_THRESHOLD → WARNING` (경계값 **제외**, `f62a153`에서 `>=` → `>`로 변경, PR #15의 경계값 포함 방침을 뒤집음)
- Python `engine.py _classify_level()` 동일 기준 적용 (`WARNING_THRESHOLD=50.0`, `CRITICAL_THRESHOLD=75.0`, `>` 비교 — `ea13763`)
- `InternalService.saveFallLog()`의 알림 분기 조건도 동일 커밋에서 `>=` → `>`로 함께 수정됨

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
| ✅ | `POST /api/users/{userId}/verify-password` | PR #15 신규, 비밀번호 사전 확인 |
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
| — | `GET /api/fall-logs/{userId}/{logId}/download` | 삭제됨 (PR #15, /thumbnail로 대체) |
| ✅ | `PATCH /api/fall-logs/{userId}/{logId}/confirm` | |
| ✅ | `DELETE /api/fall-logs/{userId}/{logId}` | |

#### Settings

| 결과 | 엔드포인트 | 비고 |
|------|-----------|------|
| ✅ | `GET /api/settings/notifications/{userId}` | notificationEnabled, soundEnabled, vibrationEnabled |
| ✅ | `PUT /api/settings/notifications/{userId}` | fallSensitivity 제거됨 |
| ✅ | `GET /api/settings/retention/{userId}` | 항상 retentionDays: 30 반환 |
| — | `PUT /api/settings/retention/{userId}` | 제거됨 (서버 고정 30일) |

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
| ✅ | `WS /ws/stream` | landmark 수신·추론·score 저장·internal API 호출 확인 (v4.0 WebSocket 전환) |

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
| 2026-06-05 | `RiskLevel.kt` 경계값(50.0/75.0)이 잘못된 레벨로 분류(`>=` 사용) | ✅ `f62a153` — `>` 비교로 수정, 임계값 76f/51f → 75f/50f |
| 2026-06-24 | Python `engine.py`가 Kotlin과 다른 임계값(51.0/76.0, `>=`) 사용 | ✅ `ea13763` — 50.0/75.0, `>` 비교로 Kotlin과 통일 |
| 2026-07-13 | `camera/service.py`의 `_save_fall_log` 트리거가 하드코딩된 76/51(`>=`)을 사용해 engine.py(50/75, `>`)와 불일치 | ✅ engine.py의 `WARNING_THRESHOLD`/`CRITICAL_THRESHOLD` 상수를 import하여 사용하도록 수정 |
| 2026-07-12 | Python AI 서버 CORS 전체 오리진 허용(`allow_origins=["*"]`) — `communication-issues-analysis.md` 문제3 | ✅ PR #18 — `kotlin_internal_base` + `CORS_ORIGINS` 환경변수로 제한 |
| 2026-07-12 | Redis 영속성 미설정 — 재시작 시 JWT 블랙리스트 소멸 (`redis-operations-analysis.md`) | ✅ PR #18 — AOF(`appendonly yes`) + `redis-data` 볼륨 마운트 (단, eviction 정책은 권장한 `volatile-lru`가 아닌 `allkeys-lru` 적용) |
| — | Python AI WebSocket이 로그아웃 토큰 블랙리스트를 확인하지 않음 (`communication-issues-analysis.md` 문제1) | ⬜ 미해결 |
| — | 두 WebSocket(`/ws/stream`, `/ws/camera`) 생명주기 비동기화 (`communication-issues-analysis.md` 문제2) | ⬜ 미해결 |
| — | 낙상 감지 시 썸네일(JPEG) 저장이 WebSocket 전환 이후 데드 코드 (`jpeg_bytes` 항상 None) | 🔄 `feature/fall-log-mp4-storage`에서 MP4 저장 배관으로 전환 작업 중 (`docs/mp4-storage-migration.md`) |
