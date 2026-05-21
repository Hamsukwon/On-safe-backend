"""
카메라 서비스
- stream: JPEG 프레임 수신 → Kotlin frame 전달 → AI 추론 → Kotlin internal API로 realtime/fall-log 위임
- score: Redis에서 현재 위험 점수 조회
- status / url: Firestore devices 컬렉션 조회
"""
import uuid
from datetime import datetime, timezone

import httpx
from google.cloud.firestore_v1.base_query import FieldFilter
from app.ai.buffer import push_frame_count, should_infer, save_score, get_score, save_latest_frame, check_caution_cooldown
from app.ai.engine import infer_frame_async
from app.core.config import settings
from app.core.exceptions import not_found
from app.core.firebase import get_firestore
from app.core.storage import upload_thumbnail
from app.domain.camera.schemas import (
    StreamResponse, ScoreResponse, StatusResponse,
    CameraUrlResponse,
)

_DEVICES = "devices"


_REALTIME = "realtime_data"
_REALTIME_LIMIT = 2000


def _score_level(score: float) -> str:
    if score >= 76:
        return "위험"
    if score >= 51:
        return "주의"
    return "정상"


async def process_stream(jpeg_bytes: bytes, user_id: str, device_id: str) -> StreamResponse:
    await push_frame_count(device_id)
    await save_latest_frame(device_id, jpeg_bytes)
    await _publish_frame(user_id, jpeg_bytes)
    ready = await should_infer(device_id)

    if not ready:
        return StreamResponse(score=0.0, fall=False)

    result = await infer_frame_async(jpeg_bytes, device_id)
    score: float = result["score"]
    fall: bool = result["fall"]
    features: dict = result.get("features", {})
    level = _score_level(score)

    await save_score(user_id, score, level)
    await _save_realtime_data(user_id, features, score)
    await _update_realtime(user_id, score, level)

    log_id: str | None = None
    if score >= 76 or fall:
        log_id = await _save_fall_log(user_id, device_id, score, fall, jpeg_bytes)
    elif score >= 51:
        if await check_caution_cooldown(user_id):
            log_id = await _save_fall_log(user_id, device_id, score, fall, jpeg_bytes)

    return StreamResponse(score=score, fall=fall, log_id=log_id)


async def _save_realtime_data(user_id: str, features: dict, score: float) -> None:
    if not features:
        return
    db = get_firestore()
    allowed = [
        'neck_angle', 'neck_angular_velocity', 'neck_angular_acceleration',
        'shoulder_balance_angle', 'shoulder_balance_angular_velocity', 'shoulder_balance_angular_acceleration',
        'shoulder_left_angle', 'shoulder_left_angular_velocity', 'shoulder_left_angular_acceleration',
        'shoulder_right_angle', 'shoulder_right_angular_velocity', 'shoulder_right_angular_acceleration',
        'elbow_left_angle', 'elbow_left_angular_velocity', 'elbow_left_angular_acceleration',
        'elbow_right_angle', 'elbow_right_angular_velocity', 'elbow_right_angular_acceleration',
        'hip_left_angle', 'hip_left_angular_velocity', 'hip_left_angular_acceleration',
        'hip_right_angle', 'hip_right_angular_velocity', 'hip_right_angular_acceleration',
        'knee_left_angle', 'knee_left_angular_velocity', 'knee_left_angular_acceleration',
        'knee_right_angle', 'knee_right_angular_velocity', 'knee_right_angular_acceleration',
        'ankle_left_angle', 'ankle_left_angular_velocity', 'ankle_left_angular_acceleration',
        'ankle_right_angle', 'ankle_right_angular_velocity', 'ankle_right_angular_acceleration',
        'torso_left_angle', 'torso_left_angular_velocity', 'torso_left_angular_acceleration',
        'torso_right_angle', 'torso_right_angular_velocity', 'torso_right_angular_acceleration',
        'spine_angle', 'spine_angular_velocity', 'spine_angular_acceleration',
        'center_speed', 'center_acceleration', 'center_displacement',
        'center_velocity_change', 'center_mean_speed', 'center_mean_acceleration',
    ]
    data = {k: float(v) for k, v in features.items() if k in allowed}
    data["user_id"] = user_id
    data["risk_score"] = score
    data["timestamp"] = datetime.now(timezone.utc)

    col = db.collection(_REALTIME)
    await col.add(data)

    # 최신 2000개 유지 — 복합 인덱스(user_id ASC, timestamp DESC) 필요
    try:
        old_docs = await col.where(filter=FieldFilter("user_id", "==", user_id)) \
            .order_by("timestamp", direction="DESCENDING") \
            .offset(_REALTIME_LIMIT).get()
        for doc in old_docs:
            await doc.reference.delete()
    except Exception as e:
        print(f"[camera.service] realtime_data 정리 오류 (Firestore 복합 인덱스 미생성): {e}")


async def _publish_frame(user_id: str, jpeg_bytes: bytes) -> None:
    try:
        async with httpx.AsyncClient() as client:
            await client.post(
                f"{settings.kotlin_internal_base}/internal/frame/{user_id}",
                content=jpeg_bytes,
                headers={"Content-Type": "application/octet-stream"},
                timeout=3.0,
            )
    except Exception as e:
        print(f"[camera.service] /internal/frame 호출 오류: {e}")


async def _update_realtime(user_id: str, score: float, level: str) -> None:
    try:
        async with httpx.AsyncClient() as client:
            await client.post(
                f"{settings.kotlin_internal_base}/internal/realtime",
                json={"user_id": user_id, "score": score, "level": level},
                timeout=3.0,
            )
    except Exception as e:
        print(f"[camera.service] /internal/realtime 호출 오류: {e}")


async def _save_fall_log(user_id: str, device_id: str, score: float, fall: bool, jpeg_bytes: bytes) -> str:
    log_id = str(uuid.uuid4())

    image_url: str | None = None
    try:
        image_url = await upload_thumbnail(log_id, jpeg_bytes)
    except Exception as e:
        print(f"[camera.service] 썸네일 업로드 오류 (fall-log는 계속 저장): {e}")

    try:
        async with httpx.AsyncClient() as client:
            await client.post(
                f"{settings.kotlin_internal_base}/internal/fall-log",
                json={
                    "log_id": log_id,
                    "device_id": device_id,
                    "user_id": user_id,
                    "score": score,
                    "fall": fall,
                    "is_confirmed": False,
                    "image_url": image_url,  # GCS 경로 or 에뮬레이터 URL
                },
                timeout=3.0,
            )
    except Exception as e:
        print(f"[camera.service] /internal/fall-log 호출 오류: {e}")
    return log_id


async def get_score_for_user(user_id: str) -> ScoreResponse:
    data = await get_score(user_id)
    score = data["score"] if data else 0.0
    level = data["level"] if data else "정상"
    return ScoreResponse(user_id=user_id, score=score, level=level)


async def get_device_status(device_id: str) -> StatusResponse:
    db = get_firestore()
    doc = await db.collection(_DEVICES).document(device_id).get()
    if not doc.exists:
        raise not_found("기기를 찾을 수 없습니다")
    d = doc.to_dict()
    return StatusResponse(
        device_id=device_id,
        status=d.get("status", "offline"),
        last_seen=str(d.get("last_seen", "")),
    )


async def get_camera_url(device_id: str) -> CameraUrlResponse:
    db = get_firestore()
    doc = await db.collection(_DEVICES).document(device_id).get()
    if not doc.exists:
        raise not_found("기기를 찾을 수 없습니다")
    d = doc.to_dict()
    return CameraUrlResponse(device_id=device_id, camera_url=d.get("camera_url"))


