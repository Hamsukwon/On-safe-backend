# Firebase Storage 파이프라인 — 운영 고려사항 분석

## 옵션 정의

| # | 옵션 | 저장 대상 |
|---|------|-----------|
| 1 | JPEG 썸네일 | 낙상 감지 시점 프레임 1장 (30~150 KB) |
| 2 | MP4 영상 클립 | 낙상 전후 N초 영상 (5~30 MB) |
| 3 | JPEG + signed URL | 옵션 1 저장, 조회 시 만료 URL 발급 |

---

## 1. 스토리지 용량 산정

### 공통 가정
- 사용자 10명 기준, 카메라 기기 10대
- 낙상 이벤트(score ≥ 76) 발생: 10회/day/사용자 (보수적 추정)
- 해상도: 480p JPEG

### 옵션 1 — JPEG 썸네일

| 항목 | 계산 | 값 |
|------|------|----|
| 파일 1개 | 480p JPEG quality 85% | ~80 KB |
| 일일 생성량 | 10명 × 10회 × 80KB | 8 MB/day |
| 월간 누적 | 8MB × 30일 | 240 MB/month |
| Firebase 무료 한도 | 5 GB | **한도 내 (4.8% 사용)** |
| 보존 30일 시 상시 저장량 | 240 MB | 무시할 수준 |

### 옵션 2 — MP4 영상 클립

| 항목 | 계산 | 값 |
|------|------|----|
| 파일 1개 | 480p H.264, 40초 | ~10 MB |
| 일일 생성량 | 10명 × 10회 × 10MB | 1 GB/day |
| Firebase 무료 한도 (download) | 1 GB/day | **첫날부터 한도 초과** |
| 월간 스토리지 비용 | 30GB × $0.026/GB | ~$0.78/month |
| **Egress 비용** | 1회 조회 × 10MB × 이벤트 수 | **$0.12/GB → 주요 비용** |

> ⚠️ MP4는 다운로드(Egress) 비용이 스토리지 비용보다 10배 이상 높을 수 있음

### 옵션 3 — JPEG + signed URL

- 스토리지: 옵션 1과 동일
- 추가 비용 없음 (서명은 로컬 서비스 계정으로 처리, 네트워크 불필요)

---

## 2. Redis 메모리 산정

### 옵션 1, 3 — 영향 없음
현재 `save_latest_frame()`은 device당 최신 1프레임(5초 TTL)만 저장.
- 1기기 × 80KB × 10기기 = 800 KB (무시할 수준)

### 옵션 2 — 원형 버퍼 추가 필요

낙상 전후 영상 클립을 만들려면 Redis에 프레임 버퍼를 보관해야 함:

| 항목 | 계산 | 값 |
|------|------|----|
| 버퍼 크기 | 30fps × 30KB × 40초(전30+후10) | 36 MB/device |
| 10기기 | 36MB × 10 | **360 MB** |
| Redis 기본 maxmemory | 설정 없으면 OS 전체 메모리 사용 | OOM 위험 |
| 권장 Redis maxmemory | 512 MB 이상 + `allkeys-lru` 정책 | 설정 변경 필수 |

> ⚠️ 기기 수 증가 시 Redis 메모리 선형 증가 → 30기기 = 1 GB+

---

## 3. CPU/처리 지연 분석

### 옵션 1

| 단계 | 처리 | 지연 |
|------|------|------|
| JPEG 업로드 | Firebase Storage HTTP PUT | 200~500 ms |
| 스트림 응답 영향 | `await upload_thumbnail()` 포함 | +200~500 ms/fall event |
| 개선 가능 | `asyncio.create_task()`로 백그라운드 처리 | 0 ms (fire-and-forget) |

### 옵션 2

| 단계 | 처리 | 지연 |
|------|------|------|
| 프레임 버퍼 수집 | 낙상 후 10초 추가 대기 | +10 sec |
| MP4 인코딩 | OpenCV `VideoWriter`, CPU 집약 | 2~10 sec |
| 업로드 | 10MB Firebase Storage PUT | 10~60 sec |
| **합계** | 백그라운드 처리 없으면 스트림 blocking | **20~80 sec** |
| 필수 처리 | `asyncio.create_task()` 백그라운드 | 필수 |

---

## 4. 비용 예측 (Firebase Spark/Blaze 기준)

### 옵션 1 — 월 비용 예측 (사용자 10명)
```
스토리지: 240 MB × $0.026/GB  = $0.006
Egress:   1회 조회 × 240MB    = $0.029
──────────────────────────────────────
총계:                            ~$0.04/month (사실상 무료)
```

### 옵션 2 — 월 비용 예측 (사용자 10명)
```
스토리지: 30 GB × $0.026/GB   = $0.78
Egress:   30 GB × $0.12/GB    = $3.60
──────────────────────────────────────
총계:                            ~$4.38/month
```

> 사용자 100명 시 옵션 2는 **$43/month**, 1000명 시 **$430/month**

### 옵션 3 — 옵션 1과 동일

---

## 5. 운영 이슈 및 대응 방안

### 보존 정책 (Retention)
- 설정 없으면 영구 누적 → 비용 지속 증가
- **Firebase Storage Lifecycle 규칙 설정 필수**:
  ```json
  {
    "lifecycle": {
      "rule": [{
        "action": {"type": "Delete"},
        "condition": {"age": 30}
      }]
    }
  }
  ```
  GCS 버킷에 적용 (`gsutil lifecycle set lifecycle.json gs://your-bucket`)
- 앱에서도 삭제 API 호출 시 Storage 파일 함께 삭제 처리 필요

### 업로드 실패 처리
- Firebase Storage 업로드 실패 시 FallLog는 반드시 저장 (graceful degradation)
- `image_url = null`인 FallLog는 정상, 클라이언트는 null 처리 필요
- 재시도: 최대 2회, timeout 5초로 제한 (스트림 지연 방지)

### 보안
| 방식 | 장점 | 단점 |
|------|------|------|
| `make_public()` | 구현 단순 | 누구나 URL 알면 접근 가능 |
| signed URL (만료 1시간) | 보안 강함 | URL 만료 시 앱에서 재발급 필요 |
| Firebase Auth 규칙 | Firebase 생태계 내 최적 | 백엔드 JWT 연동 복잡 |

→ **초기**: `make_public()` (내부 서비스 테스트 단계)  
→ **운영**: Firebase Storage Rules + Firebase Auth token 방식 권장

### AWS S3 마이그레이션 경로
`app/core/storage.py`의 내부 구현만 교체하면 됨:
```python
# Firebase → AWS S3 교체 시 변경 지점
import boto3
s3 = boto3.client("s3")
s3.put_object(Bucket="onsafe", Key=f"fall-thumbnails/{log_id}.jpg", Body=jpeg_bytes)
return f"https://onsafe.s3.amazonaws.com/fall-thumbnails/{log_id}.jpg"
```
- Firestore에 저장된 기존 Firebase URL → S3 URL 일괄 마이그레이션 스크립트 별도 필요
- 파일 자체도 Firebase → S3 복사 필요 (gsutil → aws s3 cp)

### 스케일 한계 및 전환 시점
| 지표 | Firebase Storage 적합 | 전환 검토 |
|------|----------------------|-----------|
| 사용자 수 | ~100명 | 100명 이상 (Blaze 요금 주의) |
| 월 Egress | < 5 GB | 5 GB 초과 시 비용 급증 |
| 기기 수 (옵션 2) | < 10대 | 10대 이상 Redis OOM 위험 |

---

## 6. 권장 결론

| 항목 | 권장 |
|------|------|
| **초기 구현** | **옵션 1** — JPEG 썸네일, 리스크 최소 |
| **추후 옵션 2** | Redis maxmemory 512MB 확보 후 적용 |
| **보존 정책** | 30일 lifecycle 설정 (배포 즉시) |
| **보안** | make_public() → 추후 signed URL 전환 |
| **마이그레이션** | `storage.py` 인터페이스 유지 시 S3 전환 용이 |
| **모니터링** | Firebase Console 월 4GB 초과 알림 설정 |
