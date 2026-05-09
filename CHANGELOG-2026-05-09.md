# 변경사항 정리 — 2026-05-09

브랜치: `refactor/domain-restructure`

---

## 커밋 이력 요약

### `9dc9fe5` fix: WebSocket 인증 추가 및 stopSession 연결 종료 처리

- WebSocket 핸들러에서 JWT 검증 및 userId 일치 여부 확인
- `stopSession` 호출 시 Redis control 채널로 STOP 신호 발행, `takeUntilOther`로 연결 종료
- WebSocket 연결 종료 시 `markStandby()`로 세션 상태 STANDBY 복구
- `docker-compose` kotlin-api에 `depends_on: redis` 추가

---

### `01bbfc3` feat: 실시간 카메라 스트리밍 기능 추가 (WebSocket + Redis pub/sub)

- WebSocket `/ws/camera/{userId}` 엔드포인트 추가
- 카메라 세션 상태(STANDBY / CONNECTING / LIVE) Redis 관리
- 세션 start / stop / status REST API 추가
- `/internal/frame/{userId}` 프레임 수신 → Base64 → Redis pub/sub
- `CameraStreamWebSocketHandler`: Redis 구독 → 클라이언트에 바이너리 전송
- `DeviceRepository`: `findUserIdByDeviceId` 추가
- `SecurityConfig`: `/ws/camera/**` 인증 제외 (이후 커밋에서 재설정)
- `docker-compose`: mediamtx 테스트용 RTSP 서버 추가
- 구현 사항 문서화 (`docs/camera-streaming-implementation.md`)

---

### `d8fd5b5` fix: 카메라 URL 업데이트 및 위험 레벨 color_code 매핑 버그 수정

| 파일 | 수정 내용 |
|---|---|
| `DeviceRepository` | 기기 문서 없을 때 `updateCameraUrl`이 무시되던 문제 → `update` → `set merge` 방식으로 변경 |
| `CameraService` | `colorCodeOf`가 영어 레벨("danger", "warning")을 인식 못해 항상 정상(초록) 반환하던 버그 수정 |
| `CameraController` | `updateCameraUrl` PathVariable `userId` → `deviceId` 수정 |

---

### `a1da93a` refactor: DetectionLog → FallLog 도메인 신설 및 내부 DTO 교체

- `logs` 도메인 신설: `FallLog` entity · repo · service · controller 추가
- `SaveFallLogRequest` DTO 추가 (internal API 요청 바디)
- `FallLogController`: 목록/상세 조회 · 확인 처리 · 삭제 엔드포인트 구현

---

### `e2e4984` refactor: FallEvent · DetectionLog 도메인 제거 및 Firestore 기반 구조 재편

- `fall` / `logs` 도메인 삭제 (`FallEvent`, `DetectionLog` 관련 entity · repo · service · controller 전체 제거)
- internal API를 FallLog 저장 + 실시간 데이터 업데이트 구조로 재구성
- `SaveDetectionLogRequest` · `ReportFallRequest` → `SaveFallLogRequest` · `UpdateRealtimeRequest` 교체
- `AuthService` 이메일 인증 및 비밀번호 재설정 플로우 정리
- `Settings` · `User` 서비스 Firestore 연동으로 전환
- Python AI camera 스키마 및 서비스 정리
- `application.yml` Redis 설정 및 docker 프로파일 추가

---

## 미반영 변경사항 (커밋 전 작업 중)

### `requirements.txt`
- `mediapipe>=0.10.0` → `mediapipe==0.10.13` (버전 고정)
- `scikit-learn>=1.3.0` 의존성 추가

### `JwtAuthenticationFilter.kt`
- WebSocket 경로(`/ws/camera/{userId}`) 접근 시 토큰의 userId와 경로의 userId 불일치 여부를 HTTP 레벨에서 검증 → 불일치 시 403 반환
- 토큰 추출 방식 개선: Authorization 헤더 우선, 없으면 `?token=` 쿼리 파라미터로 폴백 (WebSocket 업그레이드 요청 대응)

### `SecurityConfig.kt`
- `/ws/camera/**` 경로를 인증 제외 목록에서 **제거** — 필터 레벨 JWT 검증으로 보안 강화

---

## 세션 중 추가 변경사항

### [제거] `wardName` 필드 전체 삭제

API 기능에서 미사용 확인 후 전 계층에서 제거.

| 파일 | 변경 내용 |
|---|---|
| `User.kt` | `val wardName: String` 필드 제거 |
| `UserRepository.kt` | `toUser()` · `toMap()` 에서 `ward_name` 매핑 제거 |
| `UserResponse.kt` | DTO 필드 · `from()` 팩토리 매핑 제거 |
| `AuthService.kt` | `register()` 에서 `wardName = ""` 제거 |

---

### [제거] `registerDevice` 및 `AuthService` → `DeviceRepository` 의존성 삭제

로그인 시점의 기기 등록은 책임 범위 밖이며, 기기 등록은 Python API(`POST /api/devices/{userId}`)가 전담.
`AuthService`에서 Firestore `devices` 컬렉션에 직접 쓰는 코드를 제거하고, 관련 의존성도 함께 제거.

| 파일 | 변경 내용 |
|---|---|
| `DeviceRepository.kt` | `registerDevice(deviceId, userId)` 메서드 삭제 |
| `AuthService.kt` | `registerDevice()` 호출 제거 · `DeviceRepository` import · 생성자 주입 제거 |

---

### [추가] Android 로그인 후 기기 등록 API 호출 (Android 프로젝트)

Python 서버의 기기 등록 API를 로그인 성공 직후 호출하도록 추가.
- 최초 로그인: `devices` Firestore 컬렉션에 기기 문서 신규 생성
- 이후 로그인: 409(이미 등록) 응답을 정상으로 처리하고 무시

| 파일 | 변경 내용 |
|---|---|
| `PythonApiService.kt` | `DeviceRegisterRequest` DTO 추가 · `POST /api/devices/{userId}` 엔드포인트 추가 |
| `LoginActivity.kt` | 로그인 성공 후 `pythonApiService.registerDevice()` 호출 · 409 예외 처리 · `device_name`은 `Build.MODEL` 사용 |

---

### [수정] `deviceId` 생성 방식 변경 — `"${userId}_device"` → `ANDROID_ID` (Android 프로젝트)

기존 `"${userId}_device"` 방식은 userId에 종속되어 기기 고유성이 없었음.
`Settings.Secure.ANDROID_ID`로 변경하여 기기마다 고유한 ID를 사용.
에뮬레이터에서도 정상적으로 값이 반환되어 테스트 가능.

| 파일 | 변경 내용 |
|---|---|
| `LoginActivity.kt` | `deviceId` 를 `Settings.Secure.ANDROID_ID` 로 변경 · `android.provider.Settings` import 추가 |

---

### [수정] 아이디 중복확인 API 실제 연결 (Android 프로젝트)

`RegisterStep2Activity`의 중복확인 버튼이 TODO 상태로 항상 "사용 가능"을 반환하던 문제 수정.
`POST /api/auth/check-id` 실제 API 호출로 교체.

| 파일 | 변경 내용 |
|---|---|
| `ApiService.kt` | `CheckIdRequest` DTO 추가 · `POST /api/auth/check-id` 엔드포인트 추가 |
| `Registerstep2activity.kt` | TODO 제거 · 실제 API 호출로 교체 · 중복 시 빨간색 메시지·버튼 재활성화, 사용 가능 시 초록색 메시지·버튼 비활성화 |

---

### [수정] 카메라 비정상 종료 방지 + 시스템 중단 시 세션 유지 (Android 프로젝트)

**문제 1 — `BufferQueue has been abandoned` 에러**
수동 `unbindAll()` 호출이 카메라 HAL의 프레임 전달과 경쟁 조건(race condition)을 일으켜 발생.
CameraX의 `bindToLifecycle()` 자동 라이프사이클 관리에 위임하고, `PreviewView`를 `ImplementationMode.COMPATIBLE`(TextureView)으로 전환해 근본적으로 해결.

**문제 2 — 시스템 중단(잠금·홈·전화) 시 세션이 STANDBY로 전환되는 문제**
프로세스가 종료·재시작되면 메모리의 `currentState`가 초기화되어 항상 STANDBY로 시작.
SharedPreferences로 세션 활성 상태를 영속 저장해 재시작 시 자동 복구:

- `onPause()`: STREAMING 상태면 `session_active=true` 저장 (카메라 조작 없음, CameraX가 자동 처리)
- `onCreate()`: `session_active=true`면 `startRecording()` 자동 호출
- `stopRecording()` (종료 버튼): `session_active=false` 저장 + API `stopSession()` + STANDBY 전환
- 로그아웃: `session_active=false` 초기화

**테스트 결과 (17:02, PID 20112):**
- 화면 잠금 3회 반복 → `onResume` 시 매번 `currentState=STREAMING` 유지 확인
- `BufferQueue has been abandoned` 에러 없음

| 파일 | 변경 내용 |
|---|---|
| `CameraModeActivity.kt` | `PreviewView` TextureView 모드 전환 · `PREF_SESSION` SharedPreferences 추가 · `onPause()` 세션 저장 · `onCreate()` 자동 복구 · `stopRecording()` / 로그아웃 플래그 초기화 |
