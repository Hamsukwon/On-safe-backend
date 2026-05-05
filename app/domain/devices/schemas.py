from typing import Literal, Optional
from pydantic import BaseModel

DeviceStatus = Literal["active", "inactive", "offline"]


class DeviceResponse(BaseModel):
    device_id: str
    device_name: str
    status: DeviceStatus
    last_seen: Optional[str] = None


class DeviceRegisterRequest(BaseModel):
    device_id: str
    device_name: str
    camera_url: Optional[str] = None
