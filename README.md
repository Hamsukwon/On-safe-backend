# On-safe-backend

AI 기반 노인 낙상 감지 솔루션의 백엔드 서버

---

## 아키텍처 개요

```
Android App
    │ JWT
    ▼
Kotlin Spring 서버 (:8080)   ←── Python AI 서버 (:8000)
    │ Firestore / Redis            │ MediaPipe 추론
    ▼                             │ /internal/realtime
Firebase (Auth·Firestore·Storage) │ /internal/fall-log
    ▲                             │ /internal/frame/{userId}
    └──────────────────────────────┘
```

- **Python AI 서버 (FastAPI)**: 카메라 프레임 수신 → MediaPipe 골격 추출 → Decision Tree 위험도 추론 → Kotlin internal API 호출
- **Kotlin Spring 서버 (WebFlux)**: 앱 API 제공, Firestore 저장, FCM 알림, Redis pub/sub 기반 실시간 스트리밍

---

## 기술 스택

| 구분 | 기술 |
|---|---|
| Kotlin 서버 | Spring Boot 3.4, WebFlux, Kotlin Coroutines |
| Python 서버 | FastAPI, MediaPipe, scikit-learn |
| 데이터베이스 | Firebase Firestore |
| 캐시·메시징 | Redis (세션 상태, 블랙리스트, pub/sub) |
| 이메일 | AWS SES SDK (SesAsyncClient) |
| 스토리지 | Firebase Storage (GCS) — 낙상 썸네일 JPEG |
| 푸시 알림 | Firebase Cloud Messaging (FCM) |
| 인증 | JWT (JJWT 0.12.x) + Redis 블랙리스트 |
| 컨테이너 | Docker Compose (kotlin-api, python-ai, redis) |

---

## 주요 기능

- **낙상 감지 알림**: AI 추론 점수 기반 위험(≥76)/주의(51~75)/낙상 FCM 알림
- **실시간 카메라 스트리밍**: WebSocket + Redis pub/sub (`/ws/camera/{userId}`)
- **이메일 인증**: 회원가입·비밀번호 재설정 6자리 코드 (AWS SES, 3분 TTL)
- **낙상 이력 관리**: 목록·단건 조회·확인·삭제, 썸네일 Signed URL 발급
- **설정 관리**: 알림 토글 (전체·소리·진동)

---

## 프로젝트 구조

자세한 파일별 설명은 [`docs/project-structure.md`](docs/project-structure.md) 참조

```
src/main/kotlin/com/onsafe/backend/
├── config/          # Firebase, Redis, Security, SES, WebSocket, Swagger
├── common/          # 예외처리, 응답 래퍼, JWT, Storage, Firestore 확장
└── domain/
    ├── auth/        # 로그인·회원가입·이메일인증·비밀번호재설정
    ├── camera/      # 위험도 조회·URL 관리·세션·WebSocket 스트리밍
    ├── internal/    # Python AI 서버 수신 API (realtime·fall-log·frame)
    ├── logs/        # 낙상 이력 CRUD·썸네일
    ├── notification/ # FCM 알림 발송
    ├── settings/    # 알림 설정
    └── user/        # 유저 정보 관리
```

---

## 환경 변수

`.env.example` 참조. 필수 항목:

| 변수 | 설명 |
|---|---|
| `JWT_SECRET` | JWT 서명 키 |
| `AWS_SES_REGION` | SES 리전 (예: `ap-northeast-2`) |
| `AWS_SES_FROM` | 발신자 이메일 |
| `AWS_ACCESS_KEY_ID` | AWS 자격증명 |
| `AWS_SECRET_ACCESS_KEY` | AWS 자격증명 |
| `FIREBASE_STORAGE_BUCKET` | GCS 버킷명 (썸네일 Signed URL용) |
| `REDIS_HOST` | Redis 호스트 |

---

## 실행 방법

```bash
# Docker Compose (전체 스택)
docker-compose up --build

# Kotlin 서버만 (로컬)
./gradlew bootRun

# 테스트
./gradlew test
```

---

## API 문서

서버 실행 후: `http://localhost:8080/swagger-ui.html`

전체 명세: [`v3.0_onsafe_api_spec.md`](v3.0_onsafe_api_spec.md)

---

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/project-structure.md`](docs/project-structure.md) | 전체 파일 구조·역할·API 테스트 결과 |
| [`docs/ses-email-verification.md`](docs/ses-email-verification.md) | AWS SES 이메일 인증번호 발송 구현 상세 |
| [`docs/camera-streaming-implementation.md`](docs/camera-streaming-implementation.md) | 실시간 스트리밍 구현 상세 |
| [`docs/unimplemented-items.md`](docs/unimplemented-items.md) | 미구현 항목 (#1 나이/관계, MP4 영상) |
| [`CHANGELOG.md`](CHANGELOG.md) | 브랜치별 변경 이력 |
| [`v3.0_onsafe_api_spec.md`](v3.0_onsafe_api_spec.md) | v3.0 API 명세서 |
