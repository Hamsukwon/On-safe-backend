# 미구현 항목 목록

> 브랜치: `feature/parent-main` | 기준일: 2026-05-17

---

## #1 — 사용자 나이/관계 필드 (age / relation)

### 현황 (ASIS)
- `User` 엔티티, `UserResponse` DTO, `UserUpdateRequest`, `UserRepository` 어디에도 `age`, `relation` 필드 없음
- 앱 메인 화면에서 "78세, 어머니" 같은 정보 표시 필요

### 목표 (TOBE)
| 항목 | 변경 내용 |
|------|-----------|
| `User.kt` | `age: Int? = null`, `relation: String? = null` 필드 추가 |
| `UserRepository.kt` | `toUser()` — `getInt("age")`, `getString("relation")` 추가; `toMap()` — `"age"`, `"relation"` 포함 |
| `UserResponse.kt` | `age: Int?`, `relation: String?` 필드 추가; `from()` 팩토리 매핑 |
| `UserUpdateRequest.kt` | `age: Int?`, `relation: String?` 필드 추가 |

### 구현 방향
1. `User.kt` 필드 추가 (nullable, default null)
2. `UserRepository.toUser()` / `toMap()` Firestore 매핑 추가
3. `UserResponse.from()` 팩토리 업데이트
4. `UserUpdateRequest` DTO 필드 추가
5. (선택) `AuthService.register()` 초기값 null로 저장

- **예상 작업 시간**: ~30분
- **의존성**: 없음 (독립적으로 구현 가능)
- **참고 문서**: `docs/unimplemented-items.md` (이 파일)

---

## Option 2 — 낙상 영상 클립 (MP4, "영상 보기")

### 현황 (ASIS)
- 낙상 감지 시점 JPEG 1장만 Firebase Storage에 저장
- 앱 화면의 **"영상 보기"** 버튼은 실제 MP4 영상 미구현 상태
- `FallLog.imageUrl` = GCS 경로(`fall-thumbnails/{logId}.jpg`) — JPEG 전용

### 목표 (TOBE)
낙상 감지 전후 N초 분량의 MP4 클립을 스토리지에 업로드하고 앱에서 재생 가능하도록 제공.

---

### 구현 필요 항목

#### Python AI 서버
| 항목 | 내용 |
|------|------|
| Redis 원형 버퍼 | `frame_ring:{device_id}` List — 최근 N초 JPEG 프레임 보관 (LPUSH + LTRIM) |
| OpenCV MP4 인코딩 | `cv2.VideoWriter` (H.264) + `asyncio.run_in_executor` 로 CPU 블로킹 방지 |
| `storage.py` 확장 | `upload_video(log_id, mp4_bytes)` → GCS 경로 반환 |
| `camera/service.py` | `_save_fall_log()` 후 비동기 fire-and-forget으로 MP4 인코딩·업로드 |

#### Kotlin 서버
| 항목 | 내용 |
|------|------|
| `FallLog.kt` | `videoUrl: String?` 필드 추가 |
| `FallLogResponse.kt` | `hasVideo: Boolean` 노출 |
| `SaveFallLogRequest.kt` | `videoUrl: String?` 필드 추가 |
| `InternalService.kt` | `videoUrl` 매핑 추가 |
| `FallLogRepository.kt` | `video_url` Firestore 매핑 추가 |
| `FallLogController.kt` | `GET /api/fall-logs/{userId}/{logId}/video` signed URL 엔드포인트 추가 |

---

### 리소스 산정 및 비용 분석

#### 전제 조건 (기준 스펙)
| 항목 | 값 |
|------|----|
| 해상도 | 720p (1280×720) |
| 프레임레이트 | 30fps |
| 코덱 | H.264 (libx264) |
| 클립 길이 | 10초 (낙상 5초 전 + 5초 후) |
| 클립 크기 | 약 2.5 MB (720p, CRF 28 기준) |
| 스토리지 보관 | 30일 Lifecycle 자동 삭제 |

---

#### 테스트 시나리오별 비용 (AWS S3 us-east-1 기준)

> AWS S3 Standard 요금: 저장 $0.023/GB·월, PUT $0.005/1,000건, GET $0.0004/1,000건, 전송 $0.09/GB (월 100GB 무료)

##### 시나리오 A — 소규모 (사용자 10명, 이벤트 5건/일·인)
| 항목 | 계산 | 월 비용 |
|------|------|---------|
| 월 이벤트 수 | 10명 × 5건 × 30일 = 1,500건 | — |
| 누적 저장량 (30일 보관) | 1,500 × 2.5 MB = 3.75 GB | $0.09 |
| PUT 요청 | 1,500건 | $0.008 |
| GET 요청 (2회/건) | 3,000건 | $0.001 |
| 전송 (20% 조회, 2.5 MB) | 0.75 GB (무료 구간) | $0 |
| **합계** | | **~$0.10/월** |

##### 시나리오 B — 중규모 (사용자 100명, 이벤트 10건/일·인)
| 항목 | 계산 | 월 비용 |
|------|------|---------|
| 월 이벤트 수 | 100명 × 10건 × 30일 = 30,000건 | — |
| 누적 저장량 | 30,000 × 2.5 MB = 75 GB | $1.73 |
| PUT 요청 | 30,000건 | $0.15 |
| GET 요청 (2회/건) | 60,000건 | $0.024 |
| 전송 (20% 조회) | 15 GB → 무료 100GB 이하 | $0 |
| **합계** | | **~$2/월** |

##### 시나리오 C — 대규모 (사용자 1,000명, 이벤트 15건/일·인)
| 항목 | 계산 | 월 비용 |
|------|------|---------|
| 월 이벤트 수 | 1,000명 × 15건 × 30일 = 450,000건 | — |
| 누적 저장량 | 450,000 × 2.5 MB = 1,125 GB | $25.88 |
| PUT 요청 | 450,000건 | $2.25 |
| GET 요청 (2회/건) | 900,000건 | $0.36 |
| 전송 (20% 조회) | 225 GB → 초과 125 GB × $0.09 | $11.25 |
| **합계** | | **~$40/월** |

> **Firebase GCS 비교**: 저장 $0.020/GB로 S3보다 소폭 저렴. 이미 Firebase 인증 인프라 사용 중이므로 추가 설정 없이 현행 `storage.py` 확장만으로 대응 가능. 대규모 CDN 배포가 필요하면 S3 + CloudFront 전환 검토.

---

#### 부하 병목 분석

##### 1. Python AI 서버 — CPU (가장 큰 위험)
```
현재 흐름: JPEG 수신 → AI 추론 → (낙상 감지 시) JPEG 업로드
MP4 추가:  JPEG 수신 → AI 추론 → (낙상 감지 시) JPEG 업로드 + MP4 인코딩 + MP4 업로드
```
- **인코딩 소요 시간**: 720p 10초 → CPU 단일 스레드 기준 0.5~2초
- **위험**: 메인 이벤트 루프 블로킹 → 이후 프레임 누락
- **해결**: `asyncio.run_in_executor(thread_pool, encode_mp4, frames)` — 별도 스레드에서 인코딩
- **권장 컨테이너 스펙**: CPU 2코어 이상 (현행 1코어이면 인코딩 시 AI 추론 지연 발생)

##### 2. Redis 메모리 — 원형 버퍼
```
30fps × 10s × 30 KB/frame(JPEG) = 9 MB/기기
```
| 기기 수 | Redis 메모리 사용량 | 비고 |
|---------|-------------------|------|
| 10대 | 90 MB | 기본 128MB 설정으로 충분 |
| 50대 | 450 MB | Redis maxmemory 512MB 설정 필요 |
| 100대 | 900 MB | 1GB Redis 인스턴스 필요 |
| 300대 | 2.7 GB | 별도 Redis 클러스터 또는 ElastiCache 검토 |

- **절감 방법**: 버퍼링 프레임레이트를 15fps로 낮춤 → 50% 메모리 절감 (화질 영향 최소)

##### 3. 스토리지 업로드 지연 — fall-log 응답 시간
| 단계 | 현재 (JPEG) | MP4 추가 시 |
|------|-------------|------------|
| JPEG 업로드 | ~100ms | ~100ms |
| MP4 인코딩 | — | 500ms~2,000ms (비동기) |
| MP4 업로드 | — | ~200ms (비동기) |
| fall-log Kotlin 전송 | ~50ms | ~50ms |
| **클라이언트 알림 지연** | **~150ms** | **~150ms (MP4는 fire-and-forget)** |

→ fall-log 저장과 MP4 업로드를 **분리(fire-and-forget)** 하면 알림 지연에 영향 없음.
→ `video_url`은 MP4 업로드 완료 후 Firestore 문서 별도 업데이트 방식 권장.

##### 4. 네트워크 대역폭 — 동시 업로드
- 클립 1개 2.5MB, 업로드 시간 100Mbps 기준 ~0.2초
- **10개 카메라 동시 낙상 감지** 시: 25 MB 동시 전송 → 업링크 병목 가능
- 대응: 업로드 큐(세마포어) 적용, 최대 동시 업로드 수 제한

##### 5. Firestore 읽기 비용 — 영향 없음
- MP4는 GCS에 저장, signed URL은 온디맨드 발급
- Firestore 문서 읽기는 현행(`video_url` 필드 1개 추가) 범위로 증가량 미미

---

#### 구현 선행 조건
- [ ] 사용자 수요 확인 (실제 "영상 보기" 기능 요구 여부)
- [ ] 저장 비용 예산 승인 (시나리오 B~C 기준)
- [ ] Python 컨테이너 CPU 사양 확인 (최소 2코어 권장)
- [ ] Redis maxmemory 설정 검토 (동시 기기 수 × 9 MB 기준)
- [ ] `opencv-python-headless` Python 컨테이너에 추가 (`requirements.txt`)

---

### 참고 문서
- `docs/storage-operational-analysis.md` — JPEG vs MP4 vs 하이브리드 스토리지 옵션 비교

---

## 참고 문서 전체

| 문서 | 내용 |
|------|------|
| `docs/storage-operational-analysis.md` | 스토리지 옵션별 운영 비용 분석 |
| `docs/parent-main-api-spec.md` | 메인 화면 API 전체 명세 |
| `ASIS-TOBE_user-age-relation-fields.md` | #1 사용자 나이/관계 필드 상세 구현 스펙 |
| `ASIS-TOBE_falllog-video-mp4.md` | #2 낙상 영상 클립 MP4 상세 구현 스펙 |
