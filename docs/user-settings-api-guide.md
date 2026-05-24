# [백엔드] 사용자 설정 화면 API 연동 가이드

> 브랜치: `feature/user-settings`
> 작업일: 2026-05-23

---

## 변경된 파일 (7개)

| 파일 | 변경 내용 |
|------|---------|
| `domain/user/model/dto/UserResponse.kt` | address, addressDetail 응답 필드 추가 |
| `domain/user/model/dto/UserUpdateRequest.kt` | address, addressDetail, currentPassword 필드 추가 |
| `domain/user/service/UserService.kt` | 주소 수정 처리 + 현재 비밀번호 검증 로직 추가 |
| `domain/settings/model/entity/UserSettings.kt` | soundEnabled, vibrationEnabled 필드 추가 |
| `domain/settings/model/dto/NotificationSettingsRequest.kt` | soundEnabled, vibrationEnabled 추가 |
| `domain/settings/model/dto/SettingsResponse.kt` | NotificationSettingsResponse에 sound/vibration 추가 |
| `domain/settings/service/SettingsService.kt` | updateNotifications에 sound/vibration 처리 추가 |

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
    "createdAt": "2026-05-23T00:00:00"
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
    "vibrationEnabled": true,
    "fallSensitivity": "medium"
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
  "vibrationEnabled": false,
  "fallSensitivity": "low"
}
```

> `fallSensitivity` 허용값: `"low"` | `"medium"` | `"high"`

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

3. **회원탈퇴 후** 로컬 토큰 및 SharedPreferences 반드시 삭제 필요

4. **로그아웃 후** LoginActivity로 이동 전 로컬 토큰 삭제 필요
