import time

from fastapi import APIRouter, Depends, Query, WebSocket, WebSocketDisconnect
from jose import JWTError
from app.core.deps import get_current_user_id
from app.core.security import decode_token
from app.domain.camera import service
from app.domain.camera.schemas import (
    ScoreResponse, StatusResponse,
    CameraUrlResponse,
)

router = APIRouter(prefix="/api/camera", tags=["Camera"])
ws_router = APIRouter(tags=["Camera WebSocket"])


@ws_router.websocket("/ws/stream")
async def ws_stream(websocket: WebSocket, token: str = Query(...)):
    try:
        payload = decode_token(token)
        _ = payload["sub"]
    except (JWTError, KeyError):
        await websocket.close(code=1008)
        return

    await websocket.accept()

    user_id: str | None = None
    device_id: str | None = None
    last_heartbeat = 0.0

    try:
        while True:
            data = await websocket.receive_json()
            msg_type = data.get("type")

            if msg_type == "init":
                user_id = data.get("user_id")
                device_id = data.get("device_id")
                await websocket.send_json({"type": "init_ok"})

            elif msg_type == "frame":
                if not user_id or not device_id:
                    await websocket.send_json({"type": "error", "message": "init 먼저 전송 필요"})
                    continue
                landmarks = data.get("landmarks", [])
                timestamp = data.get("timestamp", 0.0)

                # 추론 성공/실패와 무관하게 "프레임이 도착했다"만 스로틀링해서 알림 —
                # 카메라 fps와 무관하게 하트비트 부하를 고정시킨다.
                now = time.monotonic()
                if now - last_heartbeat >= service.HEARTBEAT_INTERVAL_SEC:
                    await service.send_heartbeat(user_id)
                    last_heartbeat = now

                result = await service.process_frame(landmarks, timestamp, user_id, device_id)
                await websocket.send_json({
                    "type": "result",
                    "fall_score": result.score,
                    "fall": result.fall,
                    "level": result.level,
                    "log_id": result.log_id,
                })

    except WebSocketDisconnect:
        pass


@router.get("/score/{user_id}")
async def get_score(
    user_id: str,
    _: str = Depends(get_current_user_id),
) -> ScoreResponse:
    return await service.get_score_for_user(user_id)


@router.get("/status/{device_id}")
async def get_status(
    device_id: str,
    _: str = Depends(get_current_user_id),
) -> StatusResponse:
    return await service.get_device_status(device_id)


@router.get("/url/{device_id}")
async def get_camera_url(
    device_id: str,
    _: str = Depends(get_current_user_id),
) -> CameraUrlResponse:
    return await service.get_camera_url(device_id)


