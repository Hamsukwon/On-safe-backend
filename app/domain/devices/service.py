from datetime import datetime, timezone
from google.cloud.firestore_v1.base_query import FieldFilter
from app.core.exceptions import conflict
from app.core.firebase import get_firestore
from app.domain.devices.schemas import DeviceResponse, DeviceRegisterRequest

_DEVICES = "devices"


async def get_devices(user_id: str) -> dict:
    db = get_firestore()
    query = db.collection(_DEVICES).where(filter=FieldFilter("user_id", "==", user_id))
    docs = query.stream()
    devices = [
        DeviceResponse(
            device_id=doc.id,
            device_name=doc.to_dict().get("device_name", ""),
            status=doc.to_dict().get("status", "offline"),
            last_seen=str(doc.to_dict().get("last_seen", "")),
        )
        async for doc in docs
    ]
    return {"devices": devices}


async def register_device(user_id: str, req: DeviceRegisterRequest) -> dict:
    db = get_firestore()
    doc_ref = db.collection(_DEVICES).document(req.device_id)
    doc = await doc_ref.get()
    if doc.exists:
        # 소유자가 다르면 현재 user_id로 재귀속 (재로그인·기기 이전 대응)
        existing = doc.to_dict()
        if existing.get("user_id") != user_id:
            await doc_ref.update({
                "user_id": user_id,
                "device_name": req.device_name,
                "status": "inactive",
                "last_seen": datetime.now(timezone.utc),
            })
            return {"status": "updated", "device_id": req.device_id}
        raise conflict("이미 등록된 기기 ID입니다")
    await doc_ref.set({
        "device_id": req.device_id,
        "device_name": req.device_name,
        "user_id": user_id,
        "status": "inactive",
        "camera_url": req.camera_url,
        "last_seen": datetime.now(timezone.utc),
    })
    return {"status": "created", "device_id": req.device_id}
