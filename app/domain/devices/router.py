from fastapi import APIRouter, Depends, status
from app.core.deps import get_current_user_id
from app.domain.devices import service
from app.domain.devices.schemas import DeviceRegisterRequest

router = APIRouter(prefix="/api/devices", tags=["Devices"])


@router.post("/{user_id}", status_code=status.HTTP_201_CREATED)
async def register_device(
    user_id: str,
    req: DeviceRegisterRequest,
    _: str = Depends(get_current_user_id),
) -> dict:
    return await service.register_device(user_id, req)
