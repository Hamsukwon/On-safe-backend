"""
Firebase Storage 업로드 추상화 레이어.

AWS S3 마이그레이션 시 이 모듈 내부만 교체하면 됨 — 호출부(camera/service.py) 변경 불필요.
현재 구현: Firebase Storage (firebase-admin SDK) — signed URL 방식

업로드 후 GCS 경로(e.g. "fall-thumbnails/{logId}.jpg")를 반환.
Kotlin StorageService가 해당 경로로 V4 signed URL을 온디맨드 발급.
"""
import asyncio
from firebase_admin import storage as fb_storage


def _upload_thumbnail_sync(log_id: str, jpeg_bytes: bytes) -> str:
    bucket = fb_storage.bucket()
    blob = bucket.blob(f"fall-thumbnails/{log_id}.jpg")
    blob.upload_from_string(jpeg_bytes, content_type="image/jpeg")
    return blob.name  # GCS 경로 반환 (e.g. "fall-thumbnails/{logId}.jpg")


async def upload_thumbnail(log_id: str, jpeg_bytes: bytes) -> str:
    """낙상 감지 시점 JPEG를 Firebase Storage에 업로드하고 GCS 경로를 반환."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _upload_thumbnail_sync, log_id, jpeg_bytes)


# ── AWS S3 마이그레이션 시 교체할 구현 예시 ────────────────────────────────
# import boto3
# _s3 = boto3.client("s3")
# async def upload_thumbnail(log_id: str, jpeg_bytes: bytes) -> str:
#     loop = asyncio.get_event_loop()
#     def _upload():
#         _s3.put_object(Bucket="onsafe-bucket", Key=f"fall-thumbnails/{log_id}.jpg",
#                        Body=jpeg_bytes, ContentType="image/jpeg")
#         return f"fall-thumbnails/{log_id}.jpg"
#     return await loop.run_in_executor(None, _upload)
