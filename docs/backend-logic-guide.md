# OnSafe 백엔드 내부 로직 구조 가이드

> 백엔드 API가 어떻게 동작하는지 팀원들이 이해할 수 있도록 작성한 문서입니다.

---

## 빠른 요약

### 도메인 구조 (6개)

**1. AUTH — 인증/회원가입 (`/api/auth`)**

| 기능 | 흐름 |
|------|------|
| 아이디 중복확인 | Firestore users 문서 존재 여부 확인 → 중복 시 409 반환 |
| 회원가입 | 아이디 중복 확인 → 비밀번호 BCrypt 암호화 → Firestore 저장 |
| 로그인 | Firestore 조회 → BCrypt 비교 → JWT 발급 (Access + Refresh) |
| 로그아웃 | Redis 블랙리스트에 토큰 등록 (남은 만료시간 TTL) |
| 아이디 찾기 | 이름 + 이메일로 Firestore 조회 |
| 이메일 인증 | 6자리 코드 생성 → Redis 저장(3분) → AWS SES로 발송 |
| 비밀번호 재설정 | 코드 발송 → 코드 검증(Redis) → 인증 완료 플래그(10분) → 비밀번호 변경 |
| 토큰 재발급 | Refresh Token 검증 → 새 토큰 발급 → 기존 토큰 블랙리스트 |

**2. USER — 사용자 (`/api/users`)**

| 기능 | 흐름 |
|------|------|
| 정보 조회 | JWT에서 userId 추출 → Firestore 조회 |
| 정보 수정 | 비밀번호 변경 시 현재 비밀번호 검증 → Firestore 저장 |
| 비밀번호 사전 확인 | 현재 비밀번호 검증 → 일치하면 200 OK (개인정보 수정 진입 전 본인 확인용) |
| FCM 토큰 등록/갱신 | Firestore users/{userId}.fcm_token 저장 (낙상 감지 푸시 알림 수신용) |
| 회원 탈퇴 | users + settings Firestore 문서 동시 삭제 |

**3. SETTINGS — 설정 (`/api/settings`)**

| 기능 | 흐름 |
|------|------|
| 알림 설정 조회/수정 | Firestore `settings/{userId}` 조회 (없으면 기본값으로 자동 생성) |
| 보관 기간 설정 | 고정 기본값 반환 (현재 DB 미연동) |

**4. CAMERA — 카메라/기기 (`/api/camera`, `/api/devices`)**

| 기능 | 흐름 |
|------|------|
| 위험도 점수 조회 | Firestore `realtime_data/{userId}` 조회 (AI 서버가 실시간 업데이트) |
| 위험 상태 조회 | 점수 → 정상(0-50) / 주의(51-75) / 위험(76-100) 변환 |
| 스트림 URL 조회 | Firestore `devices` 컬렉션에서 카메라 URL 반환 |
| 카메라 URL 등록/수정 | deviceId 소유권 확인 → Firestore devices.camera_url merge 저장 |
| 기기 목록 조회 | Firestore WHERE user_id == userId |
| 세션 관리 | Redis에 상태 저장 (STANDBY → CONNECTING → LIVE) |
| WebSocket 실시간 스트리밍 | AI서버가 Redis에 JPEG 프레임 퍼블리시 → WebSocket 구독자에게 전달 |

**5. LOGS — 낙상 이력 (`/api/fall-logs`)**

| 기능 | 흐름 |
|------|------|
| 이력 목록 조회 | Firestore `fall_logs` WHERE user_id == userId, 최신 100건 |
| 이력 상세 조회 | fall_logs/{logId} 단건 조회 + 소유권 검증 |
| 이력 삭제 | 소유권 확인 → Firestore fall_logs/{logId} 문서 삭제 |
| 위험도 필터 | 위험(score >= 76) / 주의(score 51~75) |
| 이력 확인처리 | Firestore `is_confirmed = true` 업데이트 |
| 썸네일 조회 | GCS V4 서명 URL 생성 (1시간 유효) — ⚠️ 2026-07-13 기준 `image_url`이 저장되지 않아(아래 참고) 실질적으로 항상 404. `feature/fall-log-mp4-storage`에서 동영상 저장으로 전환 중 |

**6. INTERNAL — AI 서버 전용 (`/internal`, JWT 없음)**

| 기능 | 흐름 |
|------|------|
| 위험도 업데이트 | AI서버 → 백엔드 → Firestore `realtime_data` 저장 |
| 낙상 감지 저장 | Firestore 저장 → fall=true: "낙상 감지 경보" / score>75: "위험 수준 감지" / score>50: "주의 수준 감지" FCM 푸시 (경계값 제외, 2026-06-24 수정) |
| 프레임 전달 | AI서버 JPEG → Base64 인코딩 → Redis Pub/Sub → WebSocket 클라이언트 |

### 낙상 감지 전체 흐름

```
AI 서버 (카메라 분석)
  │
  ├─ POST /internal/realtime → Firestore 위험도 업데이트 (앱에서 조회 가능)
  │
  └─ POST /internal/fall-log → Firestore 이력 저장
                             → fall=true            → "낙상 감지 경보" FCM 푸시
                             → fall=false, score>75 → "위험 수준 감지" FCM 푸시
                             → score>50             → "주의 수준 감지" FCM 푸시
                             (경계값 제외 — 2026-06-24 수정, 50.0/75.0은 정상/주의 경계에 포함되지 않음)
```

### 데이터 모델 (Firestore)

| 컬렉션 | 주요 필드 |
|--------|-----------|
| `users` | userId, password(암호화), name, phone, mail, address, addressDetail, fcmToken, createdAt |
| `settings` | userId, notificationEnabled, soundEnabled, vibrationEnabled |
| `devices` | deviceId, userId, deviceName, cameraUrl, status, lastSeen |
| `realtime_data` | userId, score(0-100), level(정상/주의/위험), updatedAt |
| `fall_logs` | logId, deviceId, userId, score, fall, isConfirmed, imageUrl, timestamp |

---

## 1. 전체 시스템 구조

```
┌─────────────┐     JWT 인증      ┌─────────────────┐
│  Android 앱  │ ─────────────── ▶ │                 │
└─────────────┘                   │   Spring Boot   │
                                  │    백엔드 서버   │
┌─────────────┐   JWT 없이 호출   │                 │
│  Python AI  │ ─────────────── ▶ │  (/internal/*)  │
│    서버      │                   └────────┬────────┘
└─────────────┘                            │
                         ┌─────────────────┼─────────────────┐
                         ▼                 ▼                 ▼
                   ┌──────────┐    ┌──────────────┐   ┌──────────┐
                   │ Firebase  │    │    Redis     │   │ AWS SES  │
                   │Firestore │    │(캐시/메시징)  │   │ (이메일) │
                   │  + FCM   │    └──────────────┘   └──────────┘
                   │  + GCS   │
                   └──────────┘
```

### 사용 기술
| 역할 | 기술 |
|------|------|
| 서버 프레임워크 | Spring Boot 3 + Kotlin (비동기 방식) |
| 데이터베이스 | Firebase Firestore (NoSQL) |
| 캐시 / 메시징 | Redis |
| 푸시 알림 | Firebase FCM |
| 이메일 발송 | AWS SES V2 |
| 파일 저장 | Google Cloud Storage (GCS) |
| 인증 | JWT (Access Token + Refresh Token) |
| 비밀번호 암호화 | BCrypt |

---

## 2. JWT 인증 흐름

앱에서 대부분의 API를 호출할 때 **JWT 토큰**이 필요합니다.

### 토큰 발급 (로그인)
```
앱 ──▶ POST /api/auth/login (아이디 + 비밀번호)
          │
          ├─ Firestore에서 사용자 조회
          ├─ BCrypt로 비밀번호 비교
          └─ Access Token + Refresh Token 발급해서 반환
```

### API 호출 시 인증 흐름
```
앱 ──▶ 요청 헤더에 "Authorization: Bearer {토큰}" 포함
          │
          ├─ JwtAuthenticationFilter 가 토큰 검증
          │     ├─ 서명 확인
          │     ├─ 만료 여부 확인
          │     └─ Redis 블랙리스트 확인 (로그아웃된 토큰인지)
          │
          └─ 검증 통과 → 컨트롤러로 요청 전달
```

### 토큰 종류
| 토큰 | 용도 | 만료 시간 |
|------|------|-----------|
| Access Token | API 호출 시 사용 | 짧음 (수 시간) |
| Refresh Token | Access Token 재발급 | 김 (수 일~수 주) |

### 로그아웃
- 로그아웃 시 해당 토큰을 **Redis 블랙리스트**에 등록
- 이후 같은 토큰으로 요청 시 자동 차단

---

## 3. 도메인별 API 상세

### 3-1. AUTH — 인증 (`/api/auth`)

#### 아이디 중복확인 `POST /api/auth/check-id`
```
요청: userId

1. Firestore users/{userId} 문서 존재 여부 확인
2. 존재 시 409 USER_ID_ALREADY_EXISTS
3. 없으면 200 OK (사용 가능)

※ 회원가입 폼 제출 전 아이디 입력 단계에서 경량 확인용
  /register 도 동일한 체크를 하지만, 그 시점엔 전체 폼이 완성된 후라 UX 불리
```

#### 회원가입 `POST /api/auth/register`
```
요청: 아이디, 비밀번호, 이름, 전화번호, 이메일, 주소

1. 아이디 중복 확인 (Firestore)
2. 이메일 중복 확인 (Firestore)
3. 비밀번호 BCrypt 암호화
4. Firestore users 컬렉션에 저장
```

#### 로그인 `POST /api/auth/login`
```
요청: 아이디, 비밀번호, 기기 ID

1. Firestore에서 아이디로 사용자 조회
2. BCrypt로 비밀번호 비교 → 불일치 시 401 에러
3. Access Token + Refresh Token 생성
4. 응답: userId, name, accessToken, refreshToken
```

#### 로그아웃 `POST /api/auth/logout`
```
1. 현재 Access Token의 남은 만료 시간 계산
2. Redis에 "bl:{토큰}" 키로 저장 (만료 시간만큼 TTL 설정)
3. 이후 해당 토큰 사용 불가
```

#### 토큰 재발급 `POST /api/auth/refresh`
```
1. Refresh Token 유효성 검증
2. Redis 블랙리스트 확인
3. 새 Access Token + Refresh Token 발급
4. 기존 Refresh Token은 블랙리스트에 등록
```

#### 아이디 찾기 `POST /api/auth/find-id`
```
요청: 이름, 이메일

1. Firestore에서 이메일로 사용자 조회
2. 이름 일치 여부 확인
3. 아이디 앞 3자리만 보여주고 나머지는 * 처리 (예: abc***)
```

#### 이메일 인증 `POST /api/auth/send-email-code` → `POST /api/auth/verify-email-code`
```
[발송]
1. 6자리 인증 코드 랜덤 생성
2. Redis에 "email_verify:{이메일}" 키로 저장 (3분 TTL)
3. AWS SES로 이메일 발송

[검증]
1. Redis에서 코드 조회 → 없으면 만료된 코드로 에러
2. 요청한 코드와 비교
3. 일치하면 Redis 키 삭제
```

#### 비밀번호 재설정 (3단계)
```
1단계: POST /api/auth/send-reset-code
   - 아이디 + 이메일 입력
   - 사용자 존재 확인 + 이메일 일치 확인
   - 6자리 코드 생성 → Redis 저장(3분) → 이메일 발송

2단계: POST /api/auth/verify-reset-code
   - 코드 검증
   - 성공하면 Redis에 "reset_verified:{아이디}" 플래그 저장 (10분)

3단계: POST /api/auth/reset-password
   - "reset_verified:{아이디}" 플래그 확인 (없으면 에러)
   - 새 비밀번호 BCrypt 암호화 후 Firestore에 저장
   - 플래그 삭제
```

---

### 3-2. USER — 사용자 (`/api/users`, JWT 필요)

#### 사용자 정보 조회 `GET /api/users/{userId}`
```
1. JWT에서 추출한 userId와 경로의 userId 비교 → 다르면 403
2. Firestore에서 사용자 정보 조회
3. 비밀번호 제외하고 반환
```

#### 개인정보 수정 `PUT /api/users/{userId}`
```
요청: 이름, 이메일, 전화번호, 주소 (모두 선택사항)
      비밀번호 변경 시: currentPassword + password 추가

1. 본인 확인 (JWT userId == 경로 userId)
2. 비밀번호 변경 요청인 경우 → 현재 비밀번호 BCrypt 검증
3. 변경된 필드만 업데이트 후 Firestore 저장
```

#### 비밀번호 사전 확인 `POST /api/users/{userId}/verify-password`
```
요청: currentPassword

개인정보 수정 화면 진입 전 본인 확인용
1. 본인 확인 (JWT userId == 경로 userId)
2. 현재 비밀번호 BCrypt 검증
3. 일치하면 200 OK / 불일치하면 401
```

#### FCM 토큰 등록/갱신 `PUT /api/users/{userId}/fcm-token`
```
요청: fcmToken

1. 본인 확인 (JWT userId == 경로 userId)
2. Firestore users/{userId} 조회
3. fcmToken 필드만 교체 후 전체 저장 (read → copy → set)

※ 이 API가 호출되지 않으면 낙상 감지 시 FCM 토큰 null → 푸시 알림 미전달
  앱 최초 설치 후 로그인 시 및 Firebase 토큰 갱신 시 호출 필요
```

#### 회원 탈퇴 `DELETE /api/users/{userId}`
```
1. 본인 확인
2. Firestore users/{userId} 문서 삭제
3. Firestore settings/{userId} 문서 삭제 (알림 설정도 함께 삭제)
```

---

### 3-3. SETTINGS — 설정 (`/api/settings`, JWT 필요)

#### 알림 설정 조회 `GET /api/settings/notifications/{userId}`
```
1. Firestore settings/{userId} 조회
2. 없으면 기본값으로 자동 생성 후 반환
   (기본값: 알림 ON, 소리 ON, 진동 ON)
```

#### 알림 설정 수정 `PUT /api/settings/notifications/{userId}`
```
요청: notificationEnabled, soundEnabled, vibrationEnabled (선택사항)

1. 기존 설정 조회 (없으면 기본값 생성)
2. 변경된 항목만 업데이트
3. Firestore 저장
```

---

### 3-4. CAMERA — 카메라/기기 (`/api/camera`, `/api/devices`, JWT 필요)

#### 위험도 점수 조회 `GET /api/camera/score/{userId}`
```
1. Firestore realtime_data/{userId} 조회
   (AI 서버가 실시간으로 업데이트하는 데이터)
2. score(0~100), level(정상/주의/위험) 반환
```

#### 위험 상태 조회 `GET /api/camera/status/{userId}`
```
위험도 점수를 등급으로 변환해서 반환
- 0~50:  정상 (초록색 #00C853)
- 51~75: 주의 (주황색 #FFA500)
- 76~100: 위험 (빨간색 #FF0000)
```

#### 카메라 URL 등록/수정 `PUT /api/camera/url/{deviceId}`
```
요청: cameraUrl

1. Firestore devices/{deviceId}에서 소유자 userId 조회 → 없으면 404
2. 소유자 != JWT principal → 403
3. Firestore devices/{deviceId}.camera_url 만 merge 저장 (다른 필드 유지)

※ .set(merge) 사용 이유: devices 문서에는 AI 서버·기기가 관리하는
  device_name, status, last_seen 필드가 섞여 있어 전체 덮어쓰기 불가
```

#### 기기 목록 조회 `GET /api/devices/{userId}`
```
Firestore devices 컬렉션에서 user_id == userId 인 기기 목록 반환
```

#### 카메라 세션 관리 (JWT 없음 — 기기 하드웨어용)
```
시작: PUT /api/camera/session/{deviceId}/start
  → Redis에 "camera:session:{userId}:status" = "CONNECTING" 저장 (12시간 TTL)

종료: PUT /api/camera/session/{deviceId}/stop
  → Redis 키 삭제
  → Redis pub/sub으로 "STOP" 신호 발행 → WebSocket 연결 종료

상태 조회: GET /api/camera/session/{userId}/status
  → Redis에서 STANDBY / CONNECTING / LIVE 반환
```

#### WebSocket 실시간 스트리밍 `ws://서버/ws/camera/{userId}?token=...`
```
연결 과정:
1. URL의 token 파라미터로 JWT 검증
2. token의 userId == 경로 userId 확인
3. Redis 블랙리스트 확인

스트리밍 동작:
1. Redis pub/sub "camera:frames:{userId}" 채널 구독
2. AI 서버가 JPEG 프레임을 Redis로 발행
3. 첫 프레임 수신 시 세션 상태 CONNECTING → LIVE 변경
4. 프레임을 앱으로 실시간 전송
5. "camera:control:{userId}" 채널에서 STOP 수신 시 스트리밍 종료
```

---

### 3-5. LOGS — 낙상 이력 (`/api/fall-logs`, JWT 필요)

#### 이력 목록 조회 `GET /api/fall-logs/{userId}?level={등급}`
```
1. Firestore fall_logs에서 userId 기준 최신 100건 조회
2. level 파라미터로 필터링 가능
   - "위험": score > 75
   - "주의": 50 < score <= 75
   - 파라미터 없음: 전체
   (2026-06-24 수정 — 경계값 50.0/75.0 제외)
```

#### 이력 상세 조회 `GET /api/fall-logs/{userId}/{logId}`
```
1. 본인 확인 (JWT userId == 경로 userId)
2. Firestore fall_logs/{logId} 단건 조회
3. user_id 소유권 검증 (타인 logId 조회 차단)
4. 없거나 타인 소유 시 404 LOG_NOT_FOUND
```

#### 이력 삭제 `DELETE /api/fall-logs/{userId}/{logId}`
```
1. 본인 확인 (JWT userId == 경로 userId)
2. 소유권 확인 → 없거나 타인 소유 시 404 LOG_NOT_FOUND
3. Firestore fall_logs/{logId} 문서 삭제

※ confirm(확인처리)과 다름 — confirm은 is_confirmed=true로 표시만 하고 데이터 유지
  delete는 Firestore 문서 완전 삭제
```

#### 이력 건수 조회 `GET /api/fall-logs/{userId}/counts`
```
최신 100건 기준으로 전체 / 위험 / 주의 건수 반환
```

#### 낙상 이력 확인 처리 `PATCH /api/fall-logs/{userId}/{logId}/confirm`
```
Firestore의 is_confirmed 필드를 true로 업데이트
(앱에서 사용자가 확인 버튼을 눌렀을 때 호출)
```

#### 썸네일 이미지 조회 `GET /api/fall-logs/{userId}/{logId}/thumbnail`
```
1. Firestore에서 이력 조회 → imageUrl(GCS 경로) 확인
2. GCS V4 서명 URL 생성 (1시간 유효)
3. 앱에서 해당 URL로 이미지 다운로드 가능
```

---

### 3-6. INTERNAL — AI 서버 전용 (`/internal`, JWT 없음)

> Python AI 서버만 호출하는 엔드포인트입니다. 앱에서는 호출하지 않습니다.

#### 위험도 업데이트 `POST /internal/realtime`
```
AI 서버 → 백엔드

요청: userId, score(0~100), level(정상/주의/위험)

Firestore realtime_data/{userId} 에 저장
(기존 데이터 있으면 업데이트, 없으면 새로 생성)
```

#### 낙상 이력 저장 + 알림 `POST /internal/fall-log`
```
AI 서버 → 백엔드

요청: logId, deviceId, userId, score, fall(낙상여부), imageUrl, isConfirmed

1. Firestore fall_logs에 이력 저장

2. 알림 발송 조건 판단 (2026-06-24 수정 — 경계값 제외):
   - fall=true              → "낙상 감지 경보" FCM 푸시
   - fall=false, score > 75 → "위험 수준 감지" FCM 푸시
   - score > 50             → "주의 수준 감지" FCM 푸시
   - score <= 50            → 알림 없음

   ※ fall(AI 낙상 판단)과 score(위험도 점수)는 독립 조건으로, 낙상 미판정이어도 점수가 76 이상이면 별도 알림 발송

※ FCM 발송 실패해도 이력 저장은 항상 완료됩니다
```

#### 프레임 전달 `POST /internal/frame/{userId}`
```
AI 서버 → 백엔드

1. JPEG 이미지 데이터 수신
2. Base64로 인코딩
3. Redis pub/sub "camera:frames:{userId}" 채널로 발행
4. WebSocket에 연결된 앱이 프레임 수신
```

---

## 4. 낙상 감지 전체 흐름

```
카메라 기기
    │
    ▼
Python AI 서버 (영상 분석 + 모델 추론)
    │
    ├─ 매 프레임마다 ──▶ POST /internal/frame/{userId}
    │                       Redis pub/sub 발행
    │                       ↓
    │                   WebSocket으로 앱에 실시간 전송
    │
    ├─ 위험도 변경 시 ──▶ POST /internal/realtime
    │                       Firestore realtime_data 업데이트
    │                       ↓
    │                   앱: GET /api/camera/status 로 조회
    │
    └─ 낙상/위험 감지 ──▶ POST /internal/fall-log
                            Firestore fall_logs 저장
                            ↓
                        FCM 푸시 알림 → 사용자 스마트폰
```

---

## 5. 데이터 모델 (Firestore 컬렉션)

### users/{userId}
| 필드 | 타입 | 설명 |
|------|------|------|
| userId | String | 로그인 아이디 (PK) |
| password | String | BCrypt 암호화된 비밀번호 |
| name | String | 이름 |
| phone | String | 전화번호 |
| mail | String | 이메일 (중복 불가) |
| address | String? | 주소 |
| addressDetail | String? | 상세주소 |
| fcmToken | String? | FCM 푸시 토큰 |
| createdAt | Timestamp | 가입 일시 |

### settings/{userId}
| 필드 | 타입 | 기본값 |
|------|------|--------|
| userId | String | - |
| notificationEnabled | Boolean | true |
| soundEnabled | Boolean | true |
| vibrationEnabled | Boolean | true |

### devices/{deviceId}
| 필드 | 타입 | 설명 |
|------|------|------|
| deviceId | String | 기기 고유 ID (PK) |
| userId | String | 소유 사용자 ID |
| deviceName | String | 기기 이름 |
| cameraUrl | String? | 스트림 URL |
| status | String | online / offline |
| lastSeen | Timestamp? | 마지막 연결 시각 |

### realtime_data/{userId}
| 필드 | 타입 | 설명 |
|------|------|------|
| userId | String | 사용자 ID |
| score | Float | 위험도 점수 (0~100) |
| level | String | 정상 / 주의 / 위험 |
| updatedAt | Timestamp | AI 서버 마지막 갱신 시각 |

### fall_logs/{logId}
| 필드 | 타입 | 설명 |
|------|------|------|
| logId | String | 이력 고유 ID (PK) |
| userId | String | 사용자 ID |
| deviceId | String | 기기 ID |
| score | Float | 위험도 점수 |
| fall | Boolean | 낙상 감지 여부 |
| isConfirmed | Boolean | 사용자 확인 여부 |
| imageUrl | String? | GCS 썸네일 경로 — ⚠️ 2026-07-13 기준 Python이 항상 null로 전송(WebSocket 전환 이후 JPEG 미보유), `feature/fall-log-mp4-storage`에서 동영상 경로 필드로 전환 중 |
| timestamp | Timestamp | 발생 시각 |

---

## 6. Redis 사용 목록

| 키 패턴 | 값 | TTL | 용도 |
|---------|-----|-----|------|
| `bl:{토큰}` | "1" | 토큰 남은 만료시간 | 로그아웃 토큰 블랙리스트 |
| `email_verify:{이메일}` | 6자리 코드 | 3분 | 이메일 인증 코드 |
| `reset_code:{userId}` | 6자리 코드 | 3분 | 비밀번호 재설정 코드 |
| `reset_verified:{userId}` | "1" | 10분 | 비밀번호 재설정 인증 완료 플래그 |
| `camera:session:{userId}:status` | STANDBY/CONNECTING/LIVE | 12시간 | 카메라 세션 상태 |
| `camera:session:{userId}:started_at` | ISO 시각 | 12시간 | 세션 시작 시각 |
| `camera:frames:{userId}` | Base64 JPEG | - | 실시간 프레임 pub/sub |
| `camera:control:{userId}` | STOP | - | 스트리밍 제어 pub/sub |

---

## 7. 에러 응답 형식

모든 에러는 아래 형식으로 반환됩니다.

```json
{
  "status": 401,
  "message": "비밀번호가 일치하지 않습니다."
}
```

**공통**

| 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-----------|------|--------|-----------|
| `FORBIDDEN` | 403 | 접근 권한이 없습니다. | 본인 아닌 userId로 요청 시 |

**인증/회원 (`/api/auth`, `/api/users`)**

| 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-----------|------|--------|-----------|
| `USER_NOT_FOUND` | 404 | 사용자를 찾을 수 없습니다. | 로그인, 아이디 찾기, 비밀번호 재설정 |
| `USER_ID_ALREADY_EXISTS` | 409 | 이미 사용 중인 아이디입니다. | 회원가입, 아이디 중복 확인 |
| `MAIL_ALREADY_EXISTS` | 409 | 이미 사용 중인 이메일입니다. | 회원가입 |
| `INVALID_PASSWORD` | 401 | 비밀번호가 일치하지 않습니다. | 로그인, 비밀번호 사전 확인, 개인정보 수정 |
| `INVALID_TOKEN` | 401 | 유효하지 않은 토큰입니다. | JWT 검증 실패, 블랙리스트 토큰 사용 |
| `EXPIRED_TOKEN` | 401 | 만료된 토큰입니다. | 만료된 JWT로 요청 시 |
| `MAIL_NOT_MATCH` | 400 | 이메일이 일치하지 않습니다. | 비밀번호 재설정 1단계 |
| `INVALID_RESET_CODE` | 400 | 유효하지 않은 인증코드입니다. 코드가 만료되었거나 올바르지 않습니다. | 비밀번호 재설정 2단계 |
| `INVALID_EMAIL_CODE` | 400 | 유효하지 않은 인증코드입니다. 코드가 만료되었거나 올바르지 않습니다. | 이메일 인증 코드 검증 (`/api/auth/verify-email-code`) |
| `MAIL_SEND_FAILED` | 500 | 이메일 발송에 실패했습니다. | AWS SES 발송 실패 |

**카메라/기기 (`/api/camera`, `/api/devices`)**

| 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-----------|------|--------|-----------|
| `DEVICE_NOT_FOUND` | 404 | 기기를 찾을 수 없습니다. | 세션 시작/종료 시 deviceId 미존재 |
| `CAMERA_NOT_FOUND` | 404 | 카메라 정보를 찾을 수 없습니다. | 스트림 URL 조회 시 기기 미존재 |
| `REALTIME_DATA_NOT_FOUND` | 404 | 실시간 데이터가 없습니다. | AI 서버가 아직 데이터를 보내지 않은 경우 |

**낙상 이력 (`/api/fall-logs`)**

| 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-----------|------|--------|-----------|
| `LOG_NOT_FOUND` | 404 | 사고 이력을 찾을 수 없습니다. | 이력 상세 조회, 확인 처리, 삭제 시 |
| `THUMBNAIL_NOT_FOUND` | 404 | 썸네일이 존재하지 않습니다. | 이력에 `imageUrl`이 없을 때 썸네일 조회 시 |

**알림**

| 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-----------|------|--------|-----------|
| `FCM_SEND_FAILED` | 500 | 알림 전송에 실패했습니다. | Firebase FCM 전송 실패 |
