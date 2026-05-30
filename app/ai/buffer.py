"""
Redis 기반 AI 추론 지원 버퍼

Redis 키 역할:
  score:{user_id}       TTL 30s   최신 위험 점수 캐시 (GET /score 엔드포인트용)
  frame:{device_id}     TTL  5s   최신 JPEG 프레임 — 보호자 릴레이 방식 결정 전까지 미사용
  caution_cd:{user_id}  TTL 300s  WARNING 중복 방지 쿨다운

프레임 카운터 / 추론 시작 판단은 engine.py의 _frame_buffers deque가 담당한다 (Method A).
"""
import json
from redis.asyncio import Redis
from app.core.config import settings

_redis: Redis | None = None
_redis_bytes: Redis | None = None


def get_redis() -> Redis:
    global _redis
    if _redis is None:
        _redis = Redis.from_url(settings.redis_url, decode_responses=True)
    return _redis


def get_redis_bytes() -> Redis:
    global _redis_bytes
    if _redis_bytes is None:
        _redis_bytes = Redis.from_url(settings.redis_url, decode_responses=False)
    return _redis_bytes


async def save_score(user_id: str, score: float, level: str) -> None:
    r = get_redis()
    await r.set(
        f"score:{user_id}",
        json.dumps({"score": score, "level": level}),
        ex=30,
    )


async def get_score(user_id: str) -> dict | None:
    r = get_redis()
    val = await r.get(f"score:{user_id}")
    return json.loads(val) if val else None


# 보호자 릴레이 방식 결정 전까지 미사용 — Option A(JPEG 이중스트림) 선택 시 재활성화
async def save_latest_frame(device_id: str, jpeg_bytes: bytes) -> None:
    r = get_redis_bytes()
    await r.set(f"frame:{device_id}", jpeg_bytes, ex=5)


async def get_latest_frame(device_id: str) -> bytes | None:
    r = get_redis_bytes()
    return await r.get(f"frame:{device_id}")


async def check_caution_cooldown(user_id: str, ttl_seconds: int = 300) -> bool:
    """WARNING 이벤트 쿨다운 확인.
    True = 쿨다운 없음(이벤트 저장 가능), False = 쿨다운 중.
    Redis NX 플래그로 원자적 설정 — 중복 알림/로그 방지 (기본 5분)."""
    r = get_redis()
    key = f"caution_cd:{user_id}"
    result = await r.set(key, "1", ex=ttl_seconds, nx=True)
    return result is not None
