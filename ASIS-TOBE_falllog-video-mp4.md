# #2 — 낙상 영상 클립 저장 및 다운로드 (MP4)

> 브랜치: `feature/parent-main` | 기준일: 2026-05-19  
> 현재: 낙상 감지 시 JPEG 1장만 저장 → 목표: 낙상 전후 MP4 클립 저장 + 앱 재생 지원

---

## ASIS (현재 상태)

### Python AI 서버

#### `app/core/storage.py`
```python
async def upload_thumbnail(log_id: str, jpeg_bytes: bytes) -> str:
    # GCS fall-thumbnails/{log_id}.jpg 업로드 → GCS 경로 반환
```
- `upload_video()` 함수 **없음**

#### `app/ai/buffer.py`
- 최신 프레임 1장만 Redis `frame:{device_id}` (TTL 5초)에 보관
- 원형 버퍼(ring buffer) **없음** → 과거 프레임 복구 불가

#### `app/domain/camera/service.py` (`_save_fall_log`)
```python
# 낙상 감지 시:
gcs_path = await upload_thumbnail(log_id, jpeg_bytes)  # JPEG 1장만
await _call_kotlin_internal("fall-log", {..., "image_url": gcs_path})
```
- MP4 인코딩·업로드 로직 **없음**

---

### Kotlin 서버

#### `FallLog.kt`
```kotlin
data class FallLog(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean,
    val imageUrl: String?,   // GCS 경로 (JPEG)
    val timestamp: LocalDateTime
)
```
- `videoUrl` 필드 **없음**

#### `FallLogResponse.kt`
```kotlin
data class FallLogResponse(
    ...
    val hasThumbnail: Boolean,  // imageUrl != null
    // hasVideo 없음
)
```

#### `FallLogController.kt`
```
GET /api/fall-logs/{userId}/{logId}/thumbnail  → JPEG signed URL JSON
GET /api/fall-logs/{userId}/{logId}/download   → JPEG signed URL 302 redirect
# /video 엔드포인트 없음
```

#### `SaveFallLogRequest.kt`
```kotlin
data class SaveFallLogRequest(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean,
    val imageUrl: String?   // JPEG GCS 경로
    // videoUrl 없음
)
```

#### `FallLogRepository.kt`
- Firestore `fall_logs` 문서: `image_url` 필드만 매핑
- `video_url` 매핑 **없음**

---

## TOBE (목표 상태)

### 처리 흐름

```
[낙상 감지]
    │
    ├─ 즉시(동기) ────────────────────────────────────────────────────────────┐
    │   JPEG 업로드 → POST /internal/fall-log (image_url 포함)               │
    │   → Kotlin: fall_logs 저장 + FCM 알림 발송                              │
    │   클라이언트 알림 지연: ~150ms (기존과 동일)                              │
    │                                                                         ▼
    └─ 비동기(fire-and-forget) ──────────────────────────────────────────────→ 별도 스레드
        Redis ring buffer에서 과거 N초 프레임 추출
        → OpenCV H.264 인코딩 (0.5~2초 소요)
        → GCS fall-videos/{logId}.mp4 업로드
        → PATCH /internal/fall-log/{logId}/video (videoUrl 전달)
        → Kotlin: fall_logs 문서 video_url 업데이트
```

---

### Python AI 서버

#### `app/ai/buffer.py` — Redis 원형 버퍼 추가
```python
RING_BUFFER_KEY = "frame_ring:{device_id}"
RING_MAX_FRAMES = 300  # 30fps × 10초

async def push_frame_to_ring(device_id: str, jpeg_bytes: bytes):
    key = RING_BUFFER_KEY.format(device_id=device_id)
    await redis.lpush(key, jpeg_bytes)
    await redis.ltrim(key, 0, RING_MAX_FRAMES - 1)
    await redis.expire(key, 30)  # TTL 30초

async def pop_ring_frames(device_id: str) -> list[bytes]:
    key = RING_BUFFER_KEY.format(device_id=device_id)
    return await redis.lrange(key, 0, -1)  # 최신→과거 순
```

#### `app/core/storage.py` — `upload_video()` 추가
```python
async def upload_video(log_id: str, mp4_bytes: bytes) -> str:
    # GCS fall-videos/{log_id}.mp4 업로드 → GCS 경로 반환
    blob_name = f"fall-videos/{log_id}.mp4"
    # upload_thumbnail()과 동일한 방식으로 GCS 업로드
    return blob_name
```

#### `app/domain/camera/service.py` — `_save_fall_log()` 수정
```python
async def _save_fall_log(log_id, jpeg_bytes, ...):
    # 1. JPEG 업로드 (동기)
    gcs_path = await upload_thumbnail(log_id, jpeg_bytes)
    await _call_kotlin_internal("fall-log", {..., "image_url": gcs_path})

    # 2. MP4 인코딩·업로드 (fire-and-forget)
    asyncio.create_task(_encode_and_upload_video(log_id, device_id))

async def _encode_and_upload_video(log_id: str, device_id: str):
    frames = await pop_ring_frames(device_id)          # Redis에서 추출
    frames_asc = list(reversed(frames))                # 시간 순으로 정렬
    mp4_bytes = await asyncio.get_event_loop().run_in_executor(
        thread_pool, _encode_mp4, frames_asc           # CPU 블로킹 → 별도 스레드
    )
    gcs_path = await upload_video(log_id, mp4_bytes)
    await _call_kotlin_internal(f"fall-log/{log_id}/video", {"video_url": gcs_path}, method="PATCH")

def _encode_mp4(frames: list[bytes]) -> bytes:
    # cv2.VideoWriter (H.264, 720p, 30fps) → BytesIO
    ...
```

---

### Kotlin 서버

#### `FallLog.kt`
```kotlin
data class FallLog(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean,
    val imageUrl: String?,
    val videoUrl: String?,   // 추가 — GCS fall-videos/{logId}.mp4 경로
    val timestamp: LocalDateTime
)
```

#### `FallLogResponse.kt`
```kotlin
data class FallLogResponse(
    ...
    val hasThumbnail: Boolean,
    val hasVideo: Boolean,   // 추가 — videoUrl != null
)
```

#### `SaveFallLogRequest.kt`
```kotlin
data class SaveFallLogRequest(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean,
    val imageUrl: String?,
    val videoUrl: String? = null   // 추가 (초기 fall-log 저장 시 null, 인코딩 후 별도 갱신)
)
```

#### `SaveVideoUrlRequest.kt` — 신규 DTO
```kotlin
data class SaveVideoUrlRequest(
    val videoUrl: String
)
```

#### `InternalController.kt` — 새 엔드포인트 추가
```kotlin
@PatchMapping("/fall-log/{logId}/video")
suspend fun updateFallLogVideo(
    @PathVariable logId: String,
    @RequestBody request: SaveVideoUrlRequest
): ApiResponse<Unit>
```

#### `InternalService.kt`
```kotlin
suspend fun updateFallLogVideo(logId: String, videoUrl: String) {
    fallLogRepository.updateVideoUrl(logId, videoUrl)
}
```

#### `FallLogRepository.kt` — 추가
```kotlin
// toFallLog()에 videoUrl 역직렬화 추가
// toMap()에 "video_url" 직렬화 추가

suspend fun updateVideoUrl(logId: String, videoUrl: String) {
    // Firestore fall_logs/{logId} 문서 update (merge)
    firestore.collection("fall_logs").document(logId)
        .update("video_url", videoUrl).await()
}
```

#### `FallLogController.kt` — 새 엔드포인트 추가
```kotlin
@GetMapping("/{userId}/{logId}/video")
suspend fun getVideoUrl(...): ApiResponse<Map<String, String>>
// → StorageService.generateSignedUrl(log.videoUrl) → { "signed_url": "..." }

@GetMapping("/{userId}/{logId}/video/download")
suspend fun downloadVideo(...): ResponseEntity<Unit>
// → 302 redirect to signed URL
```

---

### Firestore `fall_logs` 문서 — 변경
| 필드 | 타입 | 설명 |
|------|------|------|
| `image_url` | string | GCS `fall-thumbnails/{logId}.jpg` (기존) |
| `video_url` | string | GCS `fall-videos/{logId}.mp4` **(추가, nullable)** |

---

## 구현 방향

### 수정/신규 파일 목록

#### Python (4개)
| 파일 | 변경 내용 |
|------|-----------|
| `app/ai/buffer.py` | `push_frame_to_ring()`, `pop_ring_frames()` 추가 |
| `app/core/storage.py` | `upload_video()` 추가 |
| `app/domain/camera/service.py` | `_encode_and_upload_video()` fire-and-forget 추가 |
| `requirements.txt` | `opencv-python-headless` 추가 |

#### Kotlin (6개)
| 파일 | 변경 내용 |
|------|-----------|
| `FallLog.kt` | `videoUrl: String?` 추가 |
| `FallLogResponse.kt` | `hasVideo: Boolean` 추가 + `from()` 반영 |
| `SaveFallLogRequest.kt` | `videoUrl: String? = null` 추가 |
| `SaveVideoUrlRequest.kt` | **신규** — video_url 단건 갱신 DTO |
| `InternalController.kt` | `PATCH /internal/fall-log/{logId}/video` 추가 |
| `InternalService.kt` | `updateFallLogVideo()` 추가 |
| `FallLogRepository.kt` | `video_url` Firestore 매핑 + `updateVideoUrl()` 추가 |
| `FallLogController.kt` | `GET /{logId}/video`, `GET /{logId}/video/download` 추가 |

### 주의사항

1. **비동기 분리 필수**: MP4 인코딩은 `asyncio.create_task()`로 메인 루프에서 분리.  
   동기 처리 시 이후 프레임 누락·AI 추론 지연 발생.

2. **CPU 블로킹**: `cv2.VideoWriter` H.264 인코딩(720p 10초 ≈ 0.5~2초)은  
   반드시 `run_in_executor(thread_pool, ...)` 로 별도 스레드에 위임.

3. **Redis 메모리**: 30fps × 10초 × 30KB/frame = 약 9MB/기기.  
   동시 연결 기기 수 × 9MB 기준으로 `maxmemory` 확인 필요.  
   부담 시 버퍼 프레임레이트를 15fps로 낮춰 50% 절감 가능.

4. **video_url 갱신 타이밍**: 초기 fall-log 저장 시 `video_url`은 null.  
   MP4 인코딩 완료 후 `PATCH /internal/fall-log/{logId}/video` 로 별도 업데이트.  
   앱은 `has_video: false` → polling 또는 `has_video: true` 전까지 "영상 준비 중" 표시 권장.

5. **GCS Lifecycle 확장**: `scripts/setup_storage.py`에 `fall-videos/` prefix 30일 삭제 정책 추가.

6. **동시 업로드 병목**: 업로드 큐(세마포어, 최대 3~5건)로 동시 MP4 업로드 수 제한 권장.

7. **하위 호환**: 기존 fall_logs 문서에 `video_url` 없음 → `toFallLog()`에서 nullable 처리 필수. breaking change 없음.

### 선행 조건
- [ ] Python 컨테이너 CPU 2코어 이상 확보 (인코딩 시 AI 추론 지연 방지)
- [ ] Redis `maxmemory` 설정 검토 (동시 기기 수 × 9MB 기준)
- [ ] `opencv-python-headless` Docker 이미지에 추가
- [ ] GCS `fall-videos/` Lifecycle 정책 적용 (`scripts/setup_storage.py` 수정)
- [ ] 앱(Android) — "영상 준비 중" UX 처리 (has_video: false 대응)

### 예상 작업량
- Python: ~2시간 (ring buffer + 인코딩 + storage 확장)
- Kotlin: ~1시간 (DTO 2개 + Repository + Controller + Service 추가)
- 통합 테스트: ~1시간

### 참고 문서
- `docs/unimplemented-items.md` — 비용·부하 시나리오 분석 (Option 2 섹션)
- `docs/storage-operational-analysis.md` — 스토리지 옵션별 운영 비용 비교
- `ASIS-TOBE_user-age-relation-fields.md` — 동일 구조의 다른 미구현 과제 참고
