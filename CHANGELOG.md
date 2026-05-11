# Changelog

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
