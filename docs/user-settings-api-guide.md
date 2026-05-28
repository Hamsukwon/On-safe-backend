# [백엔드] 사용자 설정 화면 API 연동 가이드

> 브랜치: `feature/ses-email-migration`
> 최종 수정일: 2026-05-25

---

## 변경된 파일

| 파일 | 변경 내용 |
|------|---------|
| `domain/user/model/dto/UserResponse.kt` | address, addressDetail 응답 필드 추가 |
| `domain/user/model/dto/UserUpdateRequest.kt` | address, addressDetail, currentPassword 필드 추가 |
| `domain/user/service/UserService.kt` | 주소 수정 처리 + 현재 비밀번호 검증 로직 추가 |
| `domain/settings/model/entity/UserSettings.kt` | soundEnabled, vibrationEnabled 필드 추가 / fallSensitivity·retentionDays 제거 |
| `domain/settings/model/dto/NotificationSettingsRequest.kt` | soundEnabled, vibrationEnabled 추가 / fallSensitivity 제거 |
| `domain/settings/model/dto/SettingsResponse.kt` | NotificationSettingsResponse에 sound/vibration 추가 / fallSensitivity 제거; RetentionSettingsResponse 고정 30일 반환 |
| `domain/settings/service/SettingsService.kt` | updateNotifications에 sound/vibration 처리 추가 / updateRetention 제거 |
| `domain/settings/controller/SettingsController.kt` | PUT /retention/{userId} 엔드포인트 제거 |
| `domain/auth/service/EmailService.kt` | Google SMTP → AWS SES SDK 전환 |
| `config/SesConfig.kt` | SesAsyncClient Bean 신규 등록 |

---

## API 명세

### 프로필 조회

```
GET /api/users/{userId}
Authorization: Bearer {accessToken}
```

**Response**
```json
{
  "success": true,
  "data": {
    "userId": "string",
    "name": "string",
    "mail": "string",
    "phone": "string",
    "address": "string | null",
    "addressDetail": "string | null",
    "createdAt": "2026-05-25T00:00:00"
  }
}
```

---

### 프로필 수정

```
PUT /api/users/{userId}
Authorization: Bearer {accessToken}
```

**Request** (모든 필드 선택 사항)
```json
{
  "name": "string",
  "currentPassword": "string",
  "password": "string",
  "mail": "string",
  "phone": "010-0000-0000",
  "address": "string",
  "addressDetail": "string"
}
```

> **주의:** `password` 변경 시 `currentPassword` 반드시 함께 전달
> 불일치 시 → `401 INVALID_PASSWORD` 에러 반환

---

### 알림 설정 조회

```
GET /api/settings/notifications/{userId}
Authorization: Bearer {accessToken}
```

**Response**
```json
{
  "success": true,
  "data": {
    "notificationEnabled": true,
    "soundEnabled": true,
    "vibrationEnabled": true
  }
}
```

---

### 알림 설정 변경

```
PUT /api/settings/notifications/{userId}
Authorization: Bearer {accessToken}
```

**Request** (모든 필드 선택 사항)
```json
{
  "notificationEnabled": false,
  "soundEnabled": false,
  "vibrationEnabled": false
}
```

> `fallSensitivity` 필드는 제거됨 — AI 서버 내부 고정값으로 관리

---

### 로그 보관 기간 조회

```
GET /api/settings/retention/{userId}
Authorization: Bearer {accessToken}
```

**Response**
```json
{ "success": true, "data": { "retentionDays": 30 } }
```

> 보관 기간은 서버 고정 **30일**입니다. 변경 API(PUT)는 제공하지 않습니다.

---

### 로그아웃

```
POST /api/auth/logout
Authorization: Bearer {accessToken}
```

**Response**
```json
{ "success": true, "message": "로그아웃 완료" }
```

---

### 회원탈퇴

```
DELETE /api/users/{userId}
Authorization: Bearer {accessToken}
```

**Response**
```json
{ "success": true, "message": "회원 탈퇴가 완료되었습니다." }
```

> 사용자 데이터 + 설정 데이터 함께 삭제됨

---

## 프론트 연동 시 주의사항

1. **비밀번호 변경**은 `PUT /api/users/{userId}` 사용
   - `POST /api/auth/reset-password`는 비밀번호 찾기(이메일 인증) 전용이므로 혼용 금지

2. **알림 토글 3개**(전체/소리/진동)는 한 번의 PUT 요청으로 전송

3. **`fallSensitivity` 필드 제거됨** — 이전에 해당 필드를 전송하던 클라이언트 코드 삭제 필요

4. **보관 기간 설정 UI** — PUT 엔드포인트가 없으므로 변경 UI 제거 필요 (항상 30일 고정)

5. **회원탈퇴 후** 로컬 토큰 및 SharedPreferences 반드시 삭제 필요

6. **로그아웃 후** LoginActivity로 이동 전 로컬 토큰 삭제 필요
