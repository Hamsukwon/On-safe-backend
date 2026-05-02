from typing import Optional
from pydantic import BaseModel


class DeviceResponse(BaseModel):
    device_id: str
    device_name: str
    status: str
    last_seen: Optional[str] = None


class DeviceRegisterRequest(BaseModel):
    device_id: str
    device_name: str
    camera_url: Optional[str] = None
