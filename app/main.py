from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.firebase import init_firebase
from app.domain.camera.router import router as camera_router
from app.domain.devices.router import router as devices_router

app = FastAPI(
    title="OnSafe AI API",
    description="AI 카메라 기반 낙상 감지 및 실시간 모니터링 — AI 추론 전용 서버 v2.0",
    version="2.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
async def startup() -> None:
    init_firebase()


app.include_router(camera_router)
app.include_router(devices_router)
