from fastapi import APIRouter, Depends, File, Form, UploadFile
from app.core.deps import get_current_user_id
from app.domain.camera import service
from app.domain.camera.schemas import (
    StreamResponse, ScoreResponse, StatusResponse,
    CameraUrlResponse, ConfirmResponse,
)

router = APIRouter(prefix="/api/camera", tags=["Camera"])


@router.post("/stream")
async def stream(
    frame: UploadFile = File(..., description="JPEG 카메라 프레임"),
    user_id: str = Form(...),
    device_id: str = Form(...),
    _: str = Depends(get_current_user_id),
) -> StreamResponse:
    jpeg_bytes = await frame.read()
    return await service.process_stream(jpeg_bytes, user_id, device_id)


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


@router.patch("/fall-logs/{log_id}/confirm")
async def confirm_fall_log(
    log_id: str,
    _: str = Depends(get_current_user_id),
) -> ConfirmResponse:
    return await service.confirm_fall_log(log_id)
