# 메인 화면 백엔드 기능 명세서 (점수 기능 제외)

> **브랜치:** `feature/parent-main`
> **작성일:** 2026-05-17
> **대상:** 보호자 모드 메인 화면 지원 백엔드 API

---

## 1. 사용자 정보 조회

| 항목 | 내용 |
|---|---|
| 메서드 | `GET` |
| 경로 | `/api/users/{userId}` |
| 인증 | `Authorization: Bearer {token}` |

**응답 예시**
```json
{
  "success": true,
  "data": {
    "user_id": "user001",
    "name": "홍길동",
    "mail": "hong@example.com",
    "phone": "010-1234-5678",
    "created_at": "2026-01-01T00:00:00"
  }
}
```

---

## 2. FCM 토큰 등록/갱신

| 항목 | 내용 |
|---|---|
| 메서드 | `PUT` |
| 경로 | `/api/users/{userId}/fcm-token` |
| 인증 | `Authorization: Bearer {token}` |

**요청 body**
```json
{
  "fcm_token": "string"
}
```

**응답 예시**
```json
{
  "success": true,
  "message": "FCM 토큰 등록 완료"
}
```

---

## 3. 카메라 세션 상태 조회

| 항목 | 내용 |
|---|---|
| 메서드 | `GET` |
| 경로 | `/api/camera/session/{userId}/status` |
| 인증 | `Authorization: Bearer {token}` |
| 비고 | 프론트에서 5초 간격 폴링 권장 |

**응답 예시**
```json
{
  "success": true,
  "data": {
    "user_id": "user001",
    "status": "LIVE",
    "started_at": "2026-05-17T10:00:00Z",
    "elapsed_seconds": 120
  }
}
```

**status 값**

| 값 | 설명 |
|---|---|
| `STANDBY` | 카메라 비활성 (촬영 대기 중) |
| `CONNECTING` | 카메라 활성화 시작 (첫 프레임 수신 대기) |
| `LIVE` | 프레임 수신 중 (실시간 스트리밍 활성) |

---

## 4. 낙상 로그 목록 조회

| 항목 | 내용 |
|---|---|
| 메서드 | `GET` |
| 경로 | `/api/fall-logs/{userId}` |
| 인증 | `Authorization: Bearer {token}` |
| 정렬 | `timestamp` 내림차순 (최신순) |
| 최대 건수 | 100건 |

**응답 예시**
```json
{
  "success": true,
  "data": {
    "logs": [
      {
        "log_id": "uuid-1234",
        "device_id": "device_001",
        "user_id": "user001",
        "score": 87.5,
        "fall": true,
        "is_confirmed": false,
        "has_thumbnail": true,
        "timestamp": "2026-05-17T14:23:00"
      }
    ]
  }
}
```

**score 기준 위험 레벨**

| 범위 | 레벨 |
|---|---|
| 0 ~ 50 | 정상 |
| 51 ~ 75 | 주의 |
| 76 ~ 100 | 위험 |

---

## 5. 낙상 로그 확인 처리

| 항목 | 내용 |
|---|---|
| 메서드 | `PATCH` |
| 경로 | `/api/fall-logs/{userId}/{logId}/confirm` |
| 인증 | `Authorization: Bearer {token}` |
| 동작 | `is_confirmed = true` 업데이트 후 수정된 로그 반환 |

**응답 예시**
```json
{
  "success": true,
  "message": "낙상 이벤트를 확인 처리했습니다.",
  "data": {
    "log_id": "uuid-1234", "device_id": "device_001",
    "user_id": "user001",
    "score": 87.5,
    "fall": true,
    "is_confirmed": true,
    "timestamp": "2026-05-17T14:23:00"
  }
}
```

---

## 6. 알림 설정 조회

| 항목 | 내용 |
|---|---|
| 메서드 | `GET` |
| 경로 | `/api/settings/notifications/{userId}` |
| 인증 | `Authorization: Bearer {token}` |
| 비고 | 토큰의 userId와 경로의 userId가 다를 경우 403 반환 |

**응답 예시**
```json
{
  "success": true,
  "data": {
    "notification_enabled": true,
    "fall_sensitivity": "medium"
  }
}
```

**fall_sensitivity 값**

| 값 | 설명 |
|---|---|
| `low` | 낮음 |
| `medium` | 보통 |
| `high` | 높음 |

---

## 7. 알림 설정 변경

| 항목 | 내용 |
|---|---|
| 메서드 | `PUT` |
| 경로 | `/api/settings/notifications/{userId}` |
| 인증 | `Authorization: Bearer {token}` |
| 비고 | 토큰의 userId와 경로의 userId가 다를 경우 403 반환 |

**요청 body**
```json
{
  "notification_enabled": true,
  "fall_sensitivity": "medium"
}
```

**응답 예시**
```json
{
  "success": true,
  "message": "알림 설정 변경 완료",
  "data": {
    "notification_enabled": true,
    "fall_sensitivity": "medium"
  }
}
```

---

## 8. 로그 보관 기간 조회

| 항목 | 내용 |
|---|---|
| 메서드 | `GET` |
| 경로 | `/api/settings/retention/{userId}` |
| 인증 | `Authorization: Bearer {token}` |
| 비고 | 토큰의 userId와 경로의 userId가 다를 경우 403 반환 |

**응답 예시**
```json
{
  "success": true,
  "data": {
    "retention_days": 30
  }
}
```

---

## 9. 로그 보관 기간 변경

| 항목 | 내용 |
|---|---|
| 메서드 | `PUT` |
| 경로 | `/api/settings/retention/{userId}` |
| 인증 | `Authorization: Bearer {token}` |
| 비고 | 토큰의 userId와 경로의 userId가 다를 경우 403 반환 |

**요청 body**
```json
{
  "retention_days": 30
}
```

**응답 예시**
```json
{
  "success": true,
  "message": "영상 보관 기간이 설정되었습니다.",
  "data": {
    "retention_days": 30
  }
}
```

---

## 10. 로그아웃

| 항목 | 내용 |
|---|---|
| 메서드 | `POST` |
| 경로 | `/api/auth/logout` |
| 인증 | `Authorization: Bearer {token}` |
| 동작 | 토큰 무효화 |

**응답 예시**
```json
{
  "success": true,
  "message": "로그아웃 완료"
}
```

---

## 구현 현황 요약

| # | 기능 | 경로 | 구현 상태 |
|---|---|---|---|
| 1 | 사용자 정보 조회 | `GET /api/users/{userId}` | ✅ |
| 2 | FCM 토큰 갱신 | `PUT /api/users/{userId}/fcm-token` | ✅ |
| 3 | 카메라 세션 상태 조회 | `GET /api/camera/session/{userId}/status` | ✅ |
| 4 | 낙상 로그 목록 조회 | `GET /api/fall-logs/{userId}` | ✅ |
| 5 | 낙상 로그 확인 처리 | `PATCH /api/fall-logs/{userId}/{logId}/confirm` | ✅ |
| 6 | 알림 설정 조회 | `GET /api/settings/notifications/{userId}` | ✅ |
| 7 | 알림 설정 변경 | `PUT /api/settings/notifications/{userId}` | ✅ |
| 8 | 로그 보관 기간 조회 | `GET /api/settings/retention/{userId}` | ✅ |
| 9 | 로그 보관 기간 변경 | `PUT /api/settings/retention/{userId}` | ✅ |
| 10 | 로그아웃 | `POST /api/auth/logout` | ✅ |
