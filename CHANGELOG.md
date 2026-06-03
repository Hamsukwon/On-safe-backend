# Changelog

## [main] - 2026-06-03

### PR #15 — 개인정보 수정 전 비밀번호 사전 확인 API 및 score 임계값 통일

**변경 파일:** `UserController.kt`, `UserService.kt`, `VerifyPasswordRequest.kt` (신규), `RiskLevel.kt`, `RiskScoreResponse.kt`, `FallLogController.kt`, `FallLogRepository.kt`, `InternalService.kt`, `NotificationController.kt` (삭제), `docs/backend-logic-guide.md` (신규)

#### Added
- **`POST /api/users/{userId}/verify-password`**: 개인정보 수정 화면 진입 전 본인 확인용 비밀번호 사전 검증 엔드포인트. 불일치 시 401 반환
- **`VerifyPasswordRequest.kt`**: 비밀번호 사전 확인 요청 DTO (`currentPassword` 필드)
- **`RiskScoreResponse.updatedAt`**: `GET /api/camera/score/{userId}` 응답에 AI 서버 마지막 갱신 시각(`updated_at`) 추가
- **`docs/backend-logic-guide.md`**: 백엔드 내부 로직 구조 가이드 신규 추가 (각 도메인 처리 흐름, 데이터 모델, FCM 알림 조건 등)

#### Changed
- **`RiskLevel.kt`**: `DANGER_THRESHOLD` 75 → 76, `WARNING_THRESHOLD` 50 → 51로 수정. `fromScore()` 조건을 `>` → `>=`로 변경하여 임계값 경계값 포함
- **`FallLogRepository.kt`, `InternalService.kt`**: 하드코딩된 점수 임계값 제거 → `RiskLevel.DANGER_THRESHOLD`, `RiskLevel.WARNING_THRESHOLD` 상수 참조로 변경

#### Removed
- **`GET /api/fall-logs/{userId}/{logId}/download`**: `/thumbnail`로 대체 가능하여 중복 엔드포인트 삭제
- **`NotificationController.kt`**: 외부 노출 불필요 판단으로 삭제 (`POST /api/notification/send` 제거). `NotificationService`는 `InternalService` 내부 호출용으로 유지

---

### PR #14 — engine에 WARNING 레벨 분류 추가 및 service 중복 로직 제거

**변경 파일:** `app/ai/engine.py`, `app/domain/camera/service.py`

#### Changed
- **`app/ai/engine.py`**: `_classify_level()` private 메서드 추가 — 추론 결과(`score`)를 `정상/주의/위험`으로 분류하여 반환 딕셔너리에 `level` 필드 포함
- **`app/domain/camera/service.py`**: `_score_level()` 중복 함수 제거 → `engine.infer_landmarks()` 반환 값의 `level` 필드를 직접 사용

---

## [Unreleased] - feature/ai-engine-migration

### 2026-05-29 — AI 추론 엔진 마이그레이션 (Step 1~6 완료)

**변경 파일:** `app/ai/engine.py`, `app/ai/buffer.py`, `app/domain/camera/router.py`, `app/domain/camera/service.py`, `app/domain/camera/schemas.py`, `app/main.py`, `Dockerfile.python`, `requirements.txt`, `docker-compose.yml`

#### Changed

- **`app/ai/engine.py`**: Decision Tree → **XGBoost** 모델 교체. `infer_frame(jpeg_bytes)` → `infer_landmarks(landmarks, device_id, timestamp)` — cv2·MediaPipe 전면 제거, Android on-device landmark JSON 직접 수신. 30프레임 슬라이딩 윈도우 + STRIDE=5 추론 주기 유지. 임계값 WARNING=51, CRITICAL=76 통일. eager load로 전환 (lazy 조건 제거)

- **`app/ai/buffer.py`**: `push_frame_count()`, `should_infer()` 제거 (Step 3). `save_latest_frame` 호출 제거 — 보호자 릴레이 보류로 미사용 상태

- **`app/domain/camera/router.py`**: `POST /api/camera/stream` (HTTP multipart) 제거 → `WS /ws/stream?token=` (WebSocket) 추가. JWT 인증을 쿼리파라미터 방식으로 전환. init/frame 메시지 분기 처리

- **`app/domain/camera/service.py`**: `process_stream()` 제거 → `process_frame(landmarks, timestamp, user_id, device_id)` 신규 추가. `_save_fall_log()` jpeg_bytes 파라미터 선택적으로 변경 (현재 None 전달)

- **`app/domain/camera/schemas.py`**: `StreamResponse`에 `level: Optional[str]` 추가, 레거시 `status` 필드 제거

- **`app/main.py`**: startup에서 `_load_models()` eager load 추가. `GET /health` 엔드포인트 추가 (`model_loaded`, `scaler_loaded` 상태 반환)

- **`Dockerfile.python`**: apt-get 시스템 라이브러리 레이어 제거 (libgl1, libglib2.0-0). opencv-python-headless 교체 스크립트 제거. pip install 단순화

- **`requirements.txt`**: `mediapipe`, `opencv-python`, `python-multipart` 제거. `xgboost>=2.0.0` 추가

- **`docker-compose.yml`**: python-ai 서비스 healthcheck 추가 (GET /health, 15초 주기, start_period 20초). 주석 MediaPipe → XGBoost 수정

#### Added

- **`pkl/xgb_model.pkl`**: XGBoost 낙상 감지 모델 (Decision Tree에서 교체)
- **`docs/ai-engine-migration-plan.md`**: Step 1~6 마이그레이션 계획 및 완료 현황
- **`docs/ai-buffer-refactor-analysis.md`**: buffer.py Step 3 설계 근거 문서
- **`docs/ai-ondevice-plan.md`**: Option C On-device 추론 설계 계획
- **`docs/ai-migration-test-report.md`**: 마이그레이션 테스트 보고서
- **`docs/ai-runtime-analysis.md`**: 런타임 병목·리소스·동시성·트랜잭션 분석
- **`v4.0_onsafe_api_spec.md`**: WebSocket+landmark 아키텍처 기준 최신 API 명세서
- **`scripts/test_engine.py`**: engine.py 단위 테스트 (서버 불필요)
- **`scripts/test_ws_stream.py`**: WebSocket 통합 테스트

#### Removed

- **`pkl/decision_tree_model.pkl`**: XGBoost로 교체
- **`v2.0_onsafe_api_spec.md`**: git rm (v3.0, v4.0으로 대체)

---

## [Unreleased] - feature/ses-email-migration

---

### 2026-05-27 — 예외 처리 전면 강화 + 서비스 단위 테스트 추가

**변경 파일:** `EmailService.kt`, `ErrorCode.kt`, `GlobalExceptionHandler.kt`, `JwtProvider.kt`, `JwtAuthenticationFilter.kt`, `NotificationService.kt`, `InternalService.kt`, `SettingsService.kt`

**신규 테스트:** `EmailServiceTest.kt`, `AuthServiceTest.kt`, `CameraServiceTest.kt`, `FallLogServiceTest.kt`, `NotificationServiceTest.kt`, `InternalServiceTest.kt`

#### Changed

- **`EmailService.kt`**: `SdkClientException` catch 블록 추가 — 네트워크 단절·타임아웃 시 기존에는 unhandled exception으로 500 반환하던 문제 해결. `SesException` catch 내 `awsErrorDetails()?.errorMessage()` null-safe 호출로 NPE 방지. 두 예외 모두 `MAIL_SEND_FAILED`로 변환

- **`JwtProvider.kt`**: `getValidationError(): ErrorCode?` 추가 — `ExpiredJwtException` → `EXPIRED_TOKEN`, 그 외 → `INVALID_TOKEN` 구분 반환. `validate()`는 `getValidationError() == null` 위임으로 단순화

- **`JwtAuthenticationFilter.kt`**: `validate()` → `getValidationError()` 전환. 만료/무효 토큰 시 필터 통과(기존) 대신 `writeErrorResponse()`로 즉시 JSON `401` 반환. 블랙리스트 토큰도 빈 응답(기존) 대신 `INVALID_TOKEN` 메시지 포함 JSON `401` 반환

- **`NotificationService.kt`**: FCM 실패 시 `NotificationResponse(status="error")` 반환(기존) → `log.warn` 후 `BusinessException(FCM_SEND_FAILED)` throw로 변경. 실패 여부가 예외로 명확히 전파됨

- **`InternalService.kt`**: `sendNotificationSafe()` private 헬퍼 추출 — `runCatching`으로 FCM 예외 흡수 + `log.error` 기록. DB 저장과 FCM 전송이 독립 동작하여 재시도 안전성 보장

- **`SettingsService.getRetentionSettings()`**: 의도적 유저 존재 검증 생략 주석 추가

#### Added

- **`ErrorCode.FCM_SEND_FAILED`**: `500` — "알림 전송에 실패했습니다." 추가

- **`GlobalExceptionHandler`**: `MethodNotAllowedException` 핸들러 추가 — 미지원 HTTP 메서드 요청 시 기존 500 대신 `405` 반환

- **테스트 51개 추가** (MockK + kotlinx-coroutines-test 기반)

| 테스트 파일 | 케이스 수 | 주요 검증 항목 |
|---|---|---|
| `EmailServiceTest` | 4 | SdkClientException·SesException → MAIL_SEND_FAILED, 성공 |
| `AuthServiceTest` | 16 | 로그인·회원가입·아이디찾기·코드검증·토큰갱신 전 예외 경로 |
| `CameraServiceTest` | 5 | REALTIME_DATA_NOT_FOUND·CAMERA_NOT_FOUND·DEVICE_NOT_FOUND·FORBIDDEN |
| `FallLogServiceTest` | 5 | LOG_NOT_FOUND·THUMBNAIL_NOT_FOUND |
| `NotificationServiceTest` | 4 | USER_NOT_FOUND·FCM 토큰 없음·FCM 성공·FCM_SEND_FAILED |
| `InternalServiceTest` | 6 | FCM 실패 시 DB 저장 보장·점수별 알림 조건·data 필드 검증 |
| `UserServiceTest` (기존) | 5 | — |
| `SettingsServiceTest` (기존) | 6 | — |

---

### 2026-05-25 — 이메일 발송 AWS SES 전환 + 설정 항목 정리

**변경 파일:** `EmailService.kt`, `SesConfig.kt` (신규), `UserSettings.kt`, `SettingsRepository.kt`, `NotificationSettingsRequest.kt`, `SettingsResponse.kt`, `SettingsService.kt`, `SettingsController.kt`, `RetentionSettingsRequest.kt` (삭제), `SettingsServiceTest.kt`

#### Changed
- **`EmailService.kt`**: Google SMTP(`JavaMailSender`) → AWS SES SDK(`SesAsyncClient`) 전환. `sesClient.sendEmail()` + 코루틴 `await()`으로 비동기 발송, `SesException` 캐치 후 `MAIL_SEND_FAILED` BusinessException 변환
- **`UserSettings.kt`**: `fallSensitivity`, `retentionDays` 필드 제거 — 알림 토글 3개(`notificationEnabled`, `soundEnabled`, `vibrationEnabled`)만 유지
- **`SettingsRepository.kt`**: `toSettings()` / `toMap()`에서 `fall_sensitivity`, `retention_days` Firestore read/write 제거
- **`SettingsResponse.kt`**: `NotificationSettingsResponse`에서 `fallSensitivity` 제거; `RetentionSettingsResponse`를 상수 30 반환 형태로 단순화
- **`SettingsService.kt`**: `updateNotifications()`에서 `fallSensitivity` copy 제거; `getRetentionSettings()`을 `RetentionSettingsResponse()` 상수 반환으로 변경; `updateRetention()` 메서드 제거
- **`SettingsController.kt`**: `PUT /api/settings/retention/{userId}` 엔드포인트 제거

#### Added
- **`SesConfig.kt`**: `SesAsyncClient` Bean 등록. `aws.ses.region` 환경변수 기반 Region 설정

#### Removed
- **`RetentionSettingsRequest.kt`**: 파일 삭제 — PUT retention 엔드포인트 제거로 사용처 없음
- **`NotificationSettingsRequest.kt`**: `fallSensitivity` 필드 제거

---

## [Unreleased] - feature/parent-main

---

### 2026-05-19 — API 전체 테스트 후 버그 수정 2건

**변경 파일:** `NotificationService.kt`, `app/domain/devices/router.py`, `app/domain/devices/service.py`

#### Fixed
- **`NotificationService.kt`**: `sendAsync()` 호출을 `try/catch`로 감쌈 — FCM 토큰이 무효하거나 만료된 경우 Firebase 예외가 전파되어 `POST /internal/fall-log`가 500을 반환하던 문제 수정. FCM 전송 실패 시 낙상 로그 Firestore 저장은 유지되고, 응답 `status:"error"`, `message:"FCM 전송 실패: ..."` 반환 후 200으로 정상 처리
- **`app/domain/devices/router.py`**: `GET /api/devices/{user_id}` 엔드포인트 추가 — 라우터에 POST만 등록되어 있어 GET 요청 시 405 반환하던 문제 수정
- **`app/domain/devices/service.py`**: `get_devices(user_id)` 함수 추가 — Firestore `devices` 컬렉션에서 `user_id` 기준으로 기기 목록을 스트리밍 조회하여 반환

---

### 2026-05-17 — Firebase Storage 썸네일 파이프라인 (#5~#7)

**변경 파일:** `app/core/storage.py` (신규), `app/core/config.py`, `app/core/firebase.py`, `app/domain/camera/service.py`, `StorageService.kt` (신규), `FallLog.kt`, `FallLogResponse.kt`, `SaveFallLogRequest.kt`, `InternalService.kt`, `FallLogRepository.kt`, `FallLogController.kt`, `FallLogService.kt`, `ErrorCode.kt`, `application.yml`, `application-docker.yml`, `.env`, `.env.example`, `docker-compose.yml`

#### Added
- **`app/core/storage.py`**: Firebase Storage 업로드 추상화 레이어. `upload_thumbnail(log_id, jpeg_bytes)` → GCS 경로(`fall-thumbnails/{logId}.jpg`) 반환. AWS S3 마이그레이션 시 이 모듈 내부만 교체하면 됨
- **`StorageService.kt`** (`common/storage`): GCS V4 Signed URL 발급 서비스. `ServiceAccountCredentials`로 인증하며 기본 1시간 유효
- **`FallLogController`** — `GET /api/fall-logs/{userId}/{logId}/thumbnail`: signed URL JSON 응답
- **`FallLogController`** — `GET /api/fall-logs/{userId}/{logId}/download`: signed URL로 302 리다이렉트
- **`ErrorCode.THUMBNAIL_NOT_FOUND`**: 썸네일 없는 로그 요청 시 404 반환
- **`scripts/setup_storage.py`**: GCS Lifecycle(30일 자동 삭제) + CORS 설정 스크립트 (gsutil/firebase CLI 불필요)
- **`scripts/test_storage_api.py`**: Storage API 통합 테스트 스크립트 (register → login → insert FallLog → thumbnail → download 흐름)
- **`gcs-lifecycle.json`**: fall-thumbnails/ 30일 자동 삭제 Lifecycle 정책
- **`storage.rules`**: Firebase Storage 보안 규칙 (서비스 계정 접근 전용)
- **`docs/storage-operational-analysis.md`**: JPEG+signed URL vs MP4 등 스토리지 옵션별 운영 비용 분석
- **`ASIS-TOBE_user-age-relation-fields.md`**: #1 사용자 나이/관계 필드 ASIS·TOBE·구현 방향 문서 (프로젝트 루트)
- **`ASIS-TOBE_falllog-video-mp4.md`**: #2 낙상 영상 클립 MP4 저장·다운로드 ASIS·TOBE·구현 방향 문서 (프로젝트 루트)

#### Changed
- **`app/domain/camera/service.py`**: 낙상 감지(`score≥76` or `fall=True`) 시 `jpeg_bytes`를 `_save_fall_log()`에 전달, 썸네일 업로드 후 GCS 경로를 Kotlin internal API로 전송
- **`FallLog.kt`**: `imageUrl: String?` 필드 추가 (GCS 경로 저장)
- **`FallLogResponse.kt`**: `imageUrl` 직접 노출 대신 `hasThumbnail: Boolean` 노출 (GCS 경로 클라이언트 비노출)
- **`SaveFallLogRequest.kt`**: `imageUrl: String?` 필드 추가
- **`InternalService.kt`**: FallLog 생성 시 `imageUrl` 매핑 추가
- **`FallLogRepository.kt`**: `toFallLog()` / `toMap()` 에서 `image_url` 필드 추가
- **`app/core/config.py`**: `firebase_storage_bucket` 설정 추가
- **`app/core/firebase.py`**: `storageBucket` 옵션 조건부 주입
- **`application.yml` / `application-docker.yml`**: `firebase.storage.bucket` 설정 추가
- **`.env`**: `FIREBASE_STORAGE_BUCKET=on-safe-f1667.appspot.com` 추가
- **`docker-compose.yml`**: mediamtx(RTSP 테스트 서버) 제거, `FIREBASE_STORAGE_BUCKET` env 주입

---

### 2026-05-17 — 기기 목록 API + 사고 이력 레벨 필터 (#3, #4)

**변경 파일:** `DeviceController.kt` (신규), `DeviceResponse.kt` (신규), `DeviceRepository.kt`, `app/domain/devices/router.py`, `app/domain/devices/schemas.py`, `app/domain/devices/service.py`, `FallLogRepository.kt`, `FallLogService.kt`, `FallLogController.kt`

#### Added
- **`GET /api/devices/{userId}`** (Kotlin): 사용자의 등록 기기 목록 조회 (`DeviceController`, `DeviceResponse`)
- **`GET /api/fall-logs/{userId}?level=위험|주의`** 레벨 필터: `FallLogRepository`에 `level` 파라미터 지원 추가
- **`GET /api/fall-logs/{userId}/counts`** (Kotlin): 전체·위험·주의 탭별 건수를 한 번의 조회에서 반환

---

### 2026-05-17 — FCM 알림 log_id/user_id 포함 + 위험 수준 알림 (#2)

**변경 파일:** `InternalService.kt`, `NotificationService.kt`, `FcmTokenUpdateRequest.kt` (신규)

#### Added
- **FCM 알림 payload**: `log_id`, `user_id` 포함으로 앱에서 알림 탭 시 해당 사고 이력으로 바로 이동 가능
- **`score≥76` 위험 알림**: 낙상 미발생이더라도 위험 점수 초과 시 보호자 알림 발송
- **`FcmTokenUpdateRequest`**: FCM 토큰 갱신 전용 DTO 분리

---

### 2026-05-17 — UserController 경로·FCM 토큰 엔드포인트 수정 (`352fa90`)

**변경 파일:** `UserController.kt`, `AuthController.kt`, `AuthService.kt`

#### Fixed
- `UserController` 경로 매핑 오류 수정: `/api/user/` → `/api/users/`
- FCM 토큰 엔드포인트 분리: `POST /api/auth/fcm-token` 제거 → `PUT /api/users/{userId}/fcm-token` 로 이전 (소유권 검증 포함)

---

### 2026-05-17 — Python-Kotlin 연동 버그 수정 및 문서 업데이트 (`9c3e8f6`)

**변경 파일:** `app/ai/engine.py`, `app/core/security.py`, `docs/parent-main-api-spec.md`

#### Fixed
- Python AI 서버 ↔ Kotlin internal API 연동 버그 수정
- JWT 보안 설정 보완

#### Docs
- **`docs/parent-main-api-spec.md`**: 메인 화면 백엔드 API 기능 명세서 추가

---

## [Unreleased] - feature/camera-streaming

---

### 2026-05-11 — 빌드 환경 개선

**변경 파일:** `Dockerfile.kotlin`

#### Changed
- **`Dockerfile.kotlin`**: Docker 내부 빌드 방식 유지하되 BuildKit 캐시 마운트(`--mount=type=cache,target=/root/.gradle`) 적용 및 HTTP 타임아웃 연장(`connectionTimeout=120s`, `socketTimeout=120s`)
  - **원인 분석**: SSL 설정 문제가 아닌 빌드 당시 일시적 네트워크 불안정 (Cloudflare CDN이 새 Docker 컨테이너 IP에서의 연결을 일시 차단)
  - **효과**: Gradle 홈 디렉터리를 빌드 간 캐시하여 의존성 재다운로드 방지, 타임아웃 연장으로 일시적 네트워크 불안정 대응

---

### 2026-05-11 — 코드 품질 개선 (dead code 제거 · 중복 정리)

**변경 파일:** `SettingsController.kt`, `ErrorCode.kt`, `AuthService.kt`, `FallLogRepository.kt`

#### Fixed
- **`SettingsController.updateNotifications`**: 반환 타입 `ApiResponse<Unit>` → `ApiResponse<NotificationSettingsResponse>` 수정, 서비스 반환값이 클라이언트에 실제로 전달되도록 수정

#### Removed
- **`ErrorCode.kt`**: 미사용 에러코드 8개 제거 — `INVALID_INPUT`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `ELDER_NOT_FOUND`, `FALL_EVENT_NOT_FOUND`, `DEVICE_ALREADY_REGISTERED`, `SETTINGS_NOT_FOUND`

#### Refactored
- **`AuthService`**: 6자리 랜덤 인증코드 생성 로직을 `generateVerificationCode()` private 함수로 추출, `sendEmailCode` · `sendResetCode` 두 곳에서 공통 사용
- **`FallLogRepository`**: Firestore 문서 존재 확인 + userId 소유권 검증 패턴을 `getDocIfOwned()` private 함수로 추출, `findByLogIdAndUserId` · `confirmByLogIdAndUserId` · `deleteByLogIdAndUserId` 세 곳에서 공통 사용

---

### 2026-05-11 — 인증 보안 강화 (refactor/domain-restructure 반영)

**변경 파일:** `JwtProvider.kt`, `JwtAuthenticationFilter.kt`, `AuthController.kt`, `AuthService.kt`

#### Added
- **로그아웃 블랙리스트**: 로그아웃 시 access token을 Redis `bl:{token}` 키로 저장, 이후 요청에서 차단
- **`JwtProvider.getRemainingExpiry()`**: 토큰 남은 만료 시간 반환 (블랙리스트 TTL 설정에 사용)
- **`AuthService.logout()`**: 토큰을 Redis 블랙리스트에 저장하는 로그아웃 처리 함수
- **비밀번호 재설정 2단계 검증**: `verifyResetCode` 성공 후 `reset_verified:{userId}` Redis 키(10분 TTL) 발급, `resetPassword`에서 해당 키 검증 후 삭제

#### Changed
- **`JwtAuthenticationFilter`**: `ReactiveStringRedisTemplate` 주입 추가, 요청마다 Redis 블랙리스트 조회 후 차단 (기존 WebSocket 경로 검증·쿼리 파라미터 토큰 추출 유지)
- **`AuthController.logout`**: `Authorization` 헤더 수신 후 `authService.logout(token)` 호출
- **`AuthService.refresh`**: 기존 refresh token 블랙리스트 등록 (토큰 재사용 방지)

---

### 2026-05-09 — 실시간 카메라 스트리밍 · 도메인 재편 (refactor/domain-restructure)

브랜치: `refactor/domain-restructure`

#### `b3495e7` refactor: wardName 제거 · WebSocket JWT 검증 강화 · 기기 등록 책임 분리

**[제거] `wardName` 필드 전체 삭제**

API 기능에서 미사용 확인 후 전 계층에서 제거.

| 파일 | 변경 내용 |
|---|---|
| `User.kt` | `val wardName: String` 필드 제거 |
| `UserRepository.kt` | `toUser()` · `toMap()` 에서 `ward_name` 매핑 제거 |
| `UserResponse.kt` | DTO 필드 · `from()` 팩토리 매핑 제거 |
| `AuthService.kt` | `register()` 에서 `wardName = ""` 제거 |

**[제거] `registerDevice` 및 `AuthService` → `DeviceRepository` 의존성 삭제**

로그인 시점의 기기 등록은 책임 범위 밖이며, 기기 등록은 Python API(`POST /api/devices/{userId}`)가 전담.

| 파일 | 변경 내용 |
|---|---|
| `DeviceRepository.kt` | `registerDevice(deviceId, userId)` 메서드 삭제 |
| `AuthService.kt` | `registerDevice()` 호출 제거 · `DeviceRepository` import · 생성자 주입 제거 |

**[수정] `JwtAuthenticationFilter`**
- WebSocket 경로(`/ws/camera/{userId}`) 접근 시 토큰의 userId와 경로의 userId 불일치 → 403 반환
- 토큰 추출: Authorization 헤더 우선, 없으면 `?token=` 쿼리 파라미터로 폴백

**[수정] `SecurityConfig`**
- `/ws/camera/**` 경로를 인증 제외 목록에서 제거 — 필터 레벨 JWT 검증으로 보안 강화

**[추가] Android — 로그인 후 기기 등록 API 호출**

| 파일 | 변경 내용 |
|---|---|
| `PythonApiService.kt` | `DeviceRegisterRequest` DTO 추가 · `POST /api/devices/{userId}` 엔드포인트 추가 |
| `LoginActivity.kt` | 로그인 성공 후 `registerDevice()` 호출 · 409(이미 등록) 정상 처리 · `device_name`은 `Build.MODEL` 사용 |

**[수정] Android — `deviceId` 생성 방식 변경**

`"${userId}_device"` → `Settings.Secure.ANDROID_ID` (기기 고유성 확보)

| 파일 | 변경 내용 |
|---|---|
| `LoginActivity.kt` | `deviceId`를 `ANDROID_ID`로 변경 · `android.provider.Settings` import 추가 |

**[수정] Android — 아이디 중복확인 API 실제 연결**

`RegisterStep2Activity`의 중복확인 버튼이 항상 "사용 가능"을 반환하던 문제 수정.

| 파일 | 변경 내용 |
|---|---|
| `ApiService.kt` | `CheckIdRequest` DTO 추가 · `POST /api/auth/check-id` 엔드포인트 추가 |
| `Registerstep2activity.kt` | TODO 제거 · 실제 API 호출로 교체 · 중복 시 빨간색 메시지·버튼 재활성화 |

**[수정] Android — 카메라 비정상 종료 방지 + 시스템 중단 시 세션 유지**

- **`BufferQueue has been abandoned` 에러**: 수동 `unbindAll()` 호출이 race condition 유발 → CameraX 자동 라이프사이클 관리에 위임, `PreviewView`를 `COMPATIBLE`(TextureView) 모드로 전환
- **시스템 중단 시 세션 STANDBY 전환 문제**: SharedPreferences로 세션 활성 상태 영속 저장, 재시작 시 자동 복구

| 파일 | 변경 내용 |
|---|---|
| `CameraModeActivity.kt` | TextureView 모드 전환 · `PREF_SESSION` SharedPreferences 추가 · `onPause()` 저장 · `onCreate()` 자동 복구 · `stopRecording()` / 로그아웃 플래그 초기화 |

---

#### `9dc9fe5` fix: WebSocket 인증 추가 및 stopSession 연결 종료 처리

- WebSocket 핸들러에서 JWT 검증 및 userId 일치 여부 확인
- `stopSession` 호출 시 Redis control 채널로 STOP 신호 발행, `takeUntilOther`로 연결 종료
- WebSocket 연결 종료 시 `markStandby()`로 세션 상태 STANDBY 복구
- `docker-compose`: kotlin-api에 `depends_on: redis` 추가

---

#### `01bbfc3` feat: 실시간 카메라 스트리밍 기능 추가 (WebSocket + Redis pub/sub)

- WebSocket `/ws/camera/{userId}` 엔드포인트 추가
- 카메라 세션 상태(STANDBY / CONNECTING / LIVE) Redis 관리
- 세션 start / stop / status REST API 추가
- `/internal/frame/{userId}` 프레임 수신 → Base64 → Redis pub/sub
- `CameraStreamWebSocketHandler`: Redis 구독 → 클라이언트에 바이너리 전송
- `DeviceRepository`: `findUserIdByDeviceId` 추가
- `docker-compose`: mediamtx 테스트용 RTSP 서버 추가
- 구현 사항 문서화 (`docs/camera-streaming-implementation.md`)

---

#### `d8fd5b5` fix: 카메라 URL 업데이트 및 위험 레벨 color_code 매핑 버그 수정

| 파일 | 수정 내용 |
|---|---|
| `DeviceRepository` | `updateCameraUrl` — `update` → `set merge` 방식으로 변경 (기기 문서 없을 때 무시되던 문제 수정) |
| `CameraService` | `colorCodeOf` — 영어 레벨("danger", "warning") 인식 못해 항상 정상(초록) 반환하던 버그 수정 |
| `CameraController` | `updateCameraUrl` PathVariable `userId` → `deviceId` 수정 |

---

#### `a1da93a` refactor: DetectionLog → FallLog 도메인 신설 및 내부 DTO 교체

- `logs` 도메인 신설: `FallLog` entity · repo · service · controller 추가
- `SaveFallLogRequest` DTO 추가 (internal API 요청 바디)
- `FallLogController`: 목록/상세 조회 · 확인 처리 · 삭제 엔드포인트 구현

---

#### `e2e4984` refactor: FallEvent · DetectionLog 도메인 제거 및 Firestore 기반 구조 재편

- `fall` / `logs` 도메인 삭제 (`FallEvent`, `DetectionLog` 관련 entity · repo · service · controller 전체 제거)
- internal API를 FallLog 저장 + 실시간 데이터 업데이트 구조로 재구성
- `SaveDetectionLogRequest` · `ReportFallRequest` → `SaveFallLogRequest` · `UpdateRealtimeRequest` 교체
- `AuthService` 이메일 인증 및 비밀번호 재설정 플로우 정리
- `Settings` · `User` 서비스 Firestore 연동으로 전환
- `application.yml` Redis 설정 및 docker 프로파일 추가
